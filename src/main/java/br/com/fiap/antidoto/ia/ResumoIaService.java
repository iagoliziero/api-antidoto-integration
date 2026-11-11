package br.com.fiap.antidoto.ia;

import br.com.fiap.antidoto.model.Ocorrencia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    static final String INSTRUCOES = """
            Você gera um RESUMO DE PRÉ-ATENDIMENTO curto e objetivo para a equipe de uma unidade de saúde.
            Use SOMENTE os dados fornecidos. Não invente informações.
            NUNCA sugira tratamento, medicamento ou dose.
            Formato: no máximo 6 linhas, em português, em texto simples (sem markdown):
            1) Animal  2) Hora da picada e tempo decorrido  3) Faixa etária
            4) Sintomas (destaque os sinais de alerta)  5) Urgência calculada e motivos  6) Unidade de destino
            """;

    /** Resposta que fala de dose ou remédio é descartada (MVP-01 §8). */
    private static final Pattern PROIBIDO = Pattern.compile(
            "\\b(doses?|mg|ml|ampolas?|medicamentos?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int TAMANHO_MAXIMO = 1200;

    private final ChatClient chatClient;
    private final ResumoModeloPadrao resumoModeloPadrao;
    private final Duration tempoLimite;

    public ResumoIaService(
            ChatClient.Builder construtorDeChat,
            ResumoModeloPadrao resumoModeloPadrao,
            @Value("${antidoto.ia.tempo-limite-em-segundos}") long tempoLimiteEmSegundos
    ) {
        this.chatClient = construtorDeChat.defaultSystem(INSTRUCOES).build();
        this.resumoModeloPadrao = resumoModeloPadrao;
        this.tempoLimite = Duration.ofSeconds(tempoLimiteEmSegundos);
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
            Optional<String> valida = validar(resposta);
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

    /** Vazia, longa demais ou falando de dose/remédio: descarta. */
    static Optional<String> validar(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return Optional.empty();
        }
        String texto = resposta.strip();
        if (texto.length() > TAMANHO_MAXIMO || PROIBIDO.matcher(texto).find()) {
            return Optional.empty();
        }
        return Optional.of(texto);
    }

    private static long milissegundosDesde(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
