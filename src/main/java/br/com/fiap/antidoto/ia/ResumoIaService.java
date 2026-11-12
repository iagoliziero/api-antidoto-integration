package br.com.fiap.antidoto.ia;

import br.com.fiap.antidoto.model.Ocorrencia;
import br.com.fiap.antidoto.model.Sintoma;
import br.com.fiap.antidoto.model.Urgencia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resumo de pré-atendimento com IA (MVP-01 §8), pelo Spring AI apontando para o Groq.
 * Recebe só os dados da triagem (ResumoModeloPadrao.dadosDaTriagem): nunca nome,
 * e-mail, telefone ou coordenadas (RN-G6). Não é um chat: roda sozinho a cada ocorrência.
 * Vazio quando a IA falha, demora mais que o tempo limite ou responde algo que não
 * pode ir para a recepção: aí vale o modelo padrão. Nunca loga o texto enviado nem o recebido.
 */
@Service
public class ResumoIaService {

    private static final Logger log = LoggerFactory.getLogger(ResumoIaService.class);

    /**
     * O prompt do MVP-01 §8 com regras a mais, porque nos testes com o Groq a IA chamou
     * dor e inchaço de "sinal de alerta", inventou motivo para a urgência, numerou as
     * linhas e apagou o destino "não escolhido": copiar urgência, motivos e destino,
     * alerta só com (ALERTA), uma informação por linha.
     */
    static final String INSTRUCOES = """
            Você gera um RESUMO DE PRÉ-ATENDIMENTO curto e objetivo para a equipe de uma unidade de saúde.
            Use SOMENTE os dados fornecidos. Não invente informações e não acrescente interpretações.
            NUNCA sugira tratamento, medicamento ou dose.
            Copie a urgência e os motivos exatamente como vieram.
            Só chame de sinal de alerta o sintoma marcado com (ALERTA).
            Copie o destino como veio, mesmo quando for "não escolhido".
            Responda em português, em texto simples (sem markdown e sem numeração), com 6 linhas nesta ordem:
            Animal: ...
            Picada: hora e tempo decorrido
            Faixa etária: ...
            Sintomas: ... (destaque os sinais de alerta)
            Urgência: nível e motivos
            Destino: unidade de destino
            """;

    /** O aquecimento depois da subida: a menor chamada possível. */
    static final String AQUECIMENTO = "Responda apenas: ok";

    /** Resposta que fala de dose ou remédio é descartada (MVP-01 §8). */
    private static final Pattern PROIBIDO = Pattern.compile(
            "\\b(doses?|mg|ml|ampolas?|medicamentos?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int TAMANHO_MAXIMO = 1200;

    /** Como cada nível aparece no texto. A IA nunca decide a urgência: outro nível, descarta. */
    private static final Map<Urgencia, Pattern> NIVEL_NO_TEXTO = Map.of(
            Urgencia.EMERGENCIA, Pattern.compile("\\bEMERG[ÊE]NCIA\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Urgencia.ALTA, Pattern.compile("\\bALTA\\b", Pattern.CASE_INSENSITIVE),
            Urgencia.MODERADA, Pattern.compile("\\bMODERADA\\b", Pattern.CASE_INSENSITIVE));

    private static final Pattern ALERTA = Pattern.compile("alerta", Pattern.CASE_INSENSITIVE);

    /** "Destino:" sem nada depois: a IA pulou um dado. */
    private static final Pattern CAMPO_VAZIO = Pattern.compile("^[^:]{1,30}:$");

    private final ChatClient chatClient;
    private final ResumoModeloPadrao resumoModeloPadrao;
    private final Duration tempoLimite;
    private final boolean iaHabilitada;

    public ResumoIaService(
            ChatClient.Builder construtorDeChat,
            ResumoModeloPadrao resumoModeloPadrao,
            @Value("${antidoto.ia.tempo-limite-em-segundos}") long tempoLimiteEmSegundos,
            @Value("${antidoto.ia.habilitada}") boolean iaHabilitada
    ) {
        this.chatClient = construtorDeChat.defaultSystem(INSTRUCOES).build();
        this.resumoModeloPadrao = resumoModeloPadrao;
        this.tempoLimite = Duration.ofSeconds(tempoLimiteEmSegundos);
        this.iaHabilitada = iaHabilitada;
    }

    /**
     * No Render grátis (0,1 de CPU), a primeira chamada carrega o cliente HTTP, o TLS e o
     * JSON e passa do tempo limite: a primeira ocorrência depois de cada subida caía no
     * modelo padrão. Uma chamada mínima logo depois da subida deixa tudo pronto.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void aquecer() {
        if (!iaHabilitada) {
            return;
        }
        long inicio = System.nanoTime();
        try {
            chatClient.prompt().system(AQUECIMENTO).user("ok").call().content();
            log.info("IA pronta (aquecimento) em {} ms", milissegundosDesde(inicio));
        } catch (RuntimeException erro) {
            log.warn("IA não respondeu ao aquecimento ({}) em {} ms",
                    erro.getClass().getSimpleName(), milissegundosDesde(inicio));
        }
    }

    public Optional<String> gerar(Ocorrencia ocorrencia, Instant agora) {
        // Montado aqui, na transação de quem chamou (a unidade é carregada sob demanda)
        String dados = "Dados da ocorrência:\n" + resumoModeloPadrao.dadosDaTriagem(ocorrencia, agora);
        long inicio = System.nanoTime();
        try {
            String resposta = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt().user(dados).call().content())
                    .orTimeout(tempoLimite.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            boolean temAlerta = ocorrencia.getSintomas().stream().anyMatch(Sintoma::isAlerta);
            Optional<String> valida = validar(resposta, ocorrencia.getUrgencia(), temAlerta);
            log.info("IA na ocorrência {}: {} em {} ms", ocorrencia.getId(),
                    valida.isPresent() ? "resumo aceito" : "resposta descartada", milissegundosDesde(inicio));
            return valida;
        } catch (RuntimeException erro) {
            Throwable causa = erro.getCause() == null ? erro : erro.getCause();
            log.warn("IA indisponível na ocorrência {} ({}) em {} ms: vale o modelo padrão",
                    ocorrencia.getId(), causa.getClass().getSimpleName(), milissegundosDesde(inicio));
            return Optional.empty();
        }
    }

    /**
     * Tira os espaços das pontas de cada linha e as linhas em branco. Descarta a resposta
     * vazia, longa demais, que fala de dose/remédio (MVP-01 §8), que traz outro nível de
     * urgência, que fala em alerta sem sintoma de alerta ou que deixou um campo vazio.
     */
    static Optional<String> validar(String resposta, Urgencia urgencia, boolean temAlerta) {
        if (resposta == null || resposta.isBlank()) {
            return Optional.empty();
        }
        String texto = Arrays.stream(resposta.strip().split("\\R"))
                .map(String::strip)
                .filter(linha -> !linha.isEmpty())
                .collect(Collectors.joining("\n"));
        boolean nivelCerto = NIVEL_NO_TEXTO.get(urgencia).matcher(texto).find()
                && NIVEL_NO_TEXTO.entrySet().stream()
                .filter(nivel -> nivel.getKey() != urgencia)
                .noneMatch(nivel -> nivel.getValue().matcher(texto).find());
        boolean campoVazio = texto.lines().anyMatch(linha -> CAMPO_VAZIO.matcher(linha).matches());

        if (texto.length() > TAMANHO_MAXIMO
                || PROIBIDO.matcher(texto).find()
                || !nivelCerto
                || (!temAlerta && ALERTA.matcher(texto).find())
                || campoVazio) {
            return Optional.empty();
        }
        return Optional.of(texto);
    }

    private static long milissegundosDesde(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
