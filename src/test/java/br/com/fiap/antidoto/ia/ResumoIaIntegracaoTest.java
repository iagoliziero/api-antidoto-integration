package br.com.fiap.antidoto.ia;

import br.com.fiap.antidoto.ApoioDeTeste;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A IA ligada de ponta a ponta, com um servidor falso no lugar do Groq (mesma API
 * da OpenAI): confere o que o Spring AI manda e o que acontece em cada resposta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResumoIaIntegracaoTest {

    enum Modo { NORMAL, COM_DOSE, LENTO, ERRO }

    private static final String RESUMO_DA_IA = """
            Animal: escorpião, criança.
            Picada há 25 min; vômitos repetidos (sinal de alerta).
            Urgência: EMERGÊNCIA (sinal de alerta; escorpião em criança).
            Destino: Hospital Exemplo Centro.""";

    private static volatile Modo modo = Modo.NORMAL;
    private static volatile String ultimoCaminho;
    private static volatile String ultimoCorpo;

    private static final HttpServer GROQ_FALSO = iniciarGroqFalso();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void apontarParaOGroqFalso(DynamicPropertyRegistry propriedades) {
        propriedades.add("antidoto.ia.habilitada", () -> "true");
        propriedades.add("antidoto.ia.tempo-limite-em-segundos", () -> "2");
        propriedades.add("spring.ai.openai.api-key", () -> "chave-de-teste");
        propriedades.add("spring.ai.openai.base-url",
                () -> "http://localhost:" + GROQ_FALSO.getAddress().getPort() + "/openai/v1");
    }

    @AfterAll
    static void desligar() {
        GROQ_FALSO.stop(0);
    }

    @BeforeEach
    void limpar() {
        modo = Modo.NORMAL;
        ultimoCaminho = null;
        ultimoCorpo = null;
    }

    private static HttpServer iniciarGroqFalso() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.createContext("/", troca -> {
                ultimoCaminho = troca.getRequestURI().getPath();
                ultimoCorpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (modo == Modo.LENTO) {
                    dormir(Duration.ofSeconds(4));
                }
                int status = modo == Modo.ERRO ? 500 : 200;
                String conteudo = modo == Modo.COM_DOSE ? "Aplicar uma dose de soro antiescorpiônico." : RESUMO_DA_IA;
                byte[] corpo = (status == 200 ? respostaDeChat(conteudo) : "{\"error\":{\"message\":\"falhou\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(status, corpo.length);
                try (OutputStream saida = troca.getResponseBody()) {
                    saida.write(corpo);
                }
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String respostaDeChat(String conteudo) {
        String texto = conteudo.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1794350000,"model":"openai/gpt-oss-120b",
                 "choices":[{"index":0,"logprobs":null,"finish_reason":"stop",
                   "message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":60,"total_tokens":180}}
                """.formatted(texto);
    }

    private static void dormir(Duration tempo) {
        try {
            Thread.sleep(tempo.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Registra escorpião em criança com vômitos e espera o resumo sair do PENDENTE. */
    private String registrarEEsperar(String token) throws Exception {
        String corpo = """
                {"tipoAnimal":"ESCORPIAO","faixaEtaria":"CRIANCA","sintomas":["VOMITOS"],"horaPicada":"%s",
                 "latitude":-23.5614,"longitude":-46.6559,"unidadeId":null}
                """.formatted(Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(25)));
        String criada = mvc.perform(post("/api/ocorrencias").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(criada, "$.id");

        for (int tentativa = 0; tentativa < 80; tentativa++) {
            String json = mvc.perform(get("/api/ocorrencias/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                    .andReturn().getResponse().getContentAsString();
            if (!"PENDENTE".equals(JsonPath.read(json, "$.statusResumo"))) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("O resumo continuou PENDENTE");
    }

    @Test
    void comAIaRespondendoOResumoFicaProntoESoComDadosDaTriagem() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);

        String ocorrencia = registrarEEsperar(token);

        assertThat((String) JsonPath.read(ocorrencia, "$.statusResumo")).isEqualTo("PRONTO");
        assertThat((String) JsonPath.read(ocorrencia, "$.resumo")).isEqualTo(RESUMO_DA_IA);

        // O que o Spring AI mandou para o "Groq"
        assertThat(ultimoCaminho).isEqualTo("/openai/v1/chat/completions");
        assertThat((String) JsonPath.read(ultimoCorpo, "$.model")).isEqualTo("openai/gpt-oss-120b");
        assertThat((String) JsonPath.read(ultimoCorpo, "$.reasoning_effort")).isEqualTo("low");
        assertThat(ultimoCorpo)
                .contains("RESUMO DE PRÉ-ATENDIMENTO")
                .contains("Animal: Escorpião · Faixa etária: criança")
                .contains("Vômitos repetidos (ALERTA)")
                // RN-G6: nada que identifique a pessoa nem a localização
                .doesNotContain("Pessoa de Teste", "@antidoto.dev", "-23.5614", "-46.6559");
    }

    @Test
    void respostaFalandoDeDoseCaiNoModeloPadrao() throws Exception {
        modo = Modo.COM_DOSE;

        String ocorrencia = registrarEEsperar(ApoioDeTeste.cadastrarEEntrar(mvc));

        assertThat((String) JsonPath.read(ocorrencia, "$.statusResumo")).isEqualTo("MODELO_PADRAO");
        assertThat((String) JsonPath.read(ocorrencia, "$.resumo")).startsWith("PRÉ-ATENDIMENTO — Antídoto");
    }

    @Test
    void iaLentaDemaisCaiNoModeloPadrao() throws Exception {
        modo = Modo.LENTO;

        String ocorrencia = registrarEEsperar(ApoioDeTeste.cadastrarEEntrar(mvc));

        assertThat((String) JsonPath.read(ocorrencia, "$.statusResumo")).isEqualTo("MODELO_PADRAO");
    }

    @Test
    void erroDaIaCaiNoModeloPadrao() throws Exception {
        modo = Modo.ERRO;

        String ocorrencia = registrarEEsperar(ApoioDeTeste.cadastrarEEntrar(mvc));

        assertThat((String) JsonPath.read(ocorrencia, "$.statusResumo")).isEqualTo("MODELO_PADRAO");
    }
}
