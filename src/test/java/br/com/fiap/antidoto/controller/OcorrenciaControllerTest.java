package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.ApoioDeTeste;
import br.com.fiap.antidoto.model.StatusResumo;
import br.com.fiap.antidoto.repository.OcorrenciaRepository;
import br.com.fiap.antidoto.repository.UnidadeSaudeRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ocorrências (MVP-01 §6.3): registrar, o resumo em segundo plano, histórico, atualizar e remover. */
@SpringBootTest
@AutoConfigureMockMvc
class OcorrenciaControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UnidadeSaudeRepository unidadeRepository;

    @Autowired
    private OcorrenciaRepository ocorrenciaRepository;

    private static String corpo(String animal, String faixa, String sintomas, Instant horaPicada, Long unidadeId) {
        return """
                {"tipoAnimal":"%s","faixaEtaria":"%s","sintomas":[%s],"horaPicada":"%s",
                 "latitude":-23.5614,"longitude":-46.6559,"unidadeId":%s}
                """.formatted(animal, faixa, sintomas, horaPicada, unidadeId);
    }

    private static Instant haMinutos(long minutos) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(minutos));
    }

    private ResultActions registrar(String token, String json) throws Exception {
        return mvc.perform(post("/api/ocorrencias").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions atualizar(String token, long id, String json) throws Exception {
        return mvc.perform(put("/api/ocorrencias/{id}", id).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long registrarERetornarId(String token) throws Exception {
        String resposta = registrar(token, corpo("ABELHA", "ADULTO", "", haMinutos(5), null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    /** O app consulta até sair do PENDENTE; aqui, no máximo 5 s. */
    private String esperarResumo(String token, long id) throws Exception {
        for (int tentativa = 0; tentativa < 50; tentativa++) {
            String json = mvc.perform(get("/api/ocorrencias/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (!"PENDENTE".equals(JsonPath.read(json, "$.statusResumo"))) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("O resumo continuou PENDENTE");
    }

    @Test
    void registraComAUrgenciaDaRnUERespondePendenteNaHora() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);
        long hospital = unidadeRepository.findByNome("Hospital Exemplo Centro").orElseThrow().getId();
        Instant horaPicada = haMinutos(25);

        String criada = registrar(token, corpo("ESCORPIAO", "CRIANCA", "\"VOMITOS\",\"DOR_LOCAL\",\"VOMITOS\"",
                horaPicada, hospital))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.tipoAnimal").value("ESCORPIAO"))
                .andExpect(jsonPath("$.faixaEtaria").value("CRIANCA"))
                .andExpect(jsonPath("$.sintomas", contains("DOR_LOCAL", "VOMITOS")))
                .andExpect(jsonPath("$.horaPicada").value(horaPicada.toString()))
                .andExpect(jsonPath("$.urgencia").value("EMERGENCIA"))
                .andExpect(jsonPath("$.motivos", contains("Sinal de alerta", "Escorpião em criança", "Criança ou idoso")))
                .andExpect(jsonPath("$.ligar192").value(true))
                .andExpect(jsonPath("$.unidadeId").value(hospital))
                .andExpect(jsonPath("$.unidadeNome").value("Hospital Exemplo Centro"))
                .andExpect(jsonPath("$.statusResumo").value("PENDENTE"))
                .andExpect(jsonPath("$.resumo").isEmpty())
                .andExpect(jsonPath("$.avisoResumo").value("Resumo gerado por IA. Não substitui avaliação profissional."))
                .andExpect(jsonPath("$.aviso").value("Procure atendimento médico imediatamente."))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andReturn().getResponse().getContentAsString();

        // Em segundo plano, o resumo fica pronto (modelo padrão), sem dados pessoais (RN-G6)
        String pronta = esperarResumo(token, ((Number) JsonPath.read(criada, "$.id")).longValue());
        assertThat((String) JsonPath.read(pronta, "$.statusResumo")).isEqualTo("MODELO_PADRAO");
        String resumo = JsonPath.read(pronta, "$.resumo");
        assertThat(resumo)
                .startsWith("PRÉ-ATENDIMENTO — Antídoto")
                .contains("Animal: Escorpião · Faixa etária: criança")
                .contains("Vômitos repetidos (ALERTA)")
                .contains("Urgência: EMERGÊNCIA — Sinal de alerta; Escorpião em criança; Criança ou idoso")
                .contains("Destino: Hospital Exemplo Centro")
                .doesNotContain("Pessoa de Teste", "@antidoto.dev", "-23.5614");
    }

    @Test
    void semUnidadeOsCamposDaUnidadeVemNulos() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);

        registrar(token, corpo("SERPENTE", "ADULTO", "", haMinutos(10), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.urgencia").value("ALTA"))
                .andExpect(jsonPath("$.ligar192").value(false))
                .andExpect(jsonPath("$.unidadeId").isEmpty())
                .andExpect(jsonPath("$.unidadeNome").isEmpty());
    }

    @Test
    void horaDaPicadaForaDoIntervaloEUnidadeInexistente() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);

        registrar(token, corpo("ESCORPIAO", "ADULTO", "", haMinutos(-30), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("horaPicada"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("A hora da picada precisa estar entre agora e 72 horas atrás"));

        registrar(token, corpo("ESCORPIAO", "ADULTO", "", haMinutos(73 * 60), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("horaPicada"));

        registrar(token, corpo("ESCORPIAO", "ADULTO", "", haMinutos(-3), null))
                .andExpect(status().isCreated());

        registrar(token, corpo("ESCORPIAO", "ADULTO", "", haMinutos(5), 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensagem").value("Unidade não encontrada."));

        registrar(token, """
                {"sintomas":["PICADA_DE_ET"],"latitude":-23.5,"longitude":-46.6}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("sintomas"));
    }

    @Test
    void historicoDoDonoMaisRecentesPrimeiroERemover() throws Exception {
        String dona = ApoioDeTeste.cadastrarEEntrar(mvc);
        String outra = ApoioDeTeste.cadastrarEEntrar(mvc);
        long primeira = registrarERetornarId(dona);
        long segunda = registrarERetornarId(dona);

        mvc.perform(get("/api/ocorrencias/minhas").header(HttpHeaders.AUTHORIZATION, dona)
                        .param("pagina", "0").param("tamanho", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo", hasSize(2)))
                .andExpect(jsonPath("$.conteudo[0].id").value(segunda))
                .andExpect(jsonPath("$.conteudo[1].id").value(primeira))
                .andExpect(jsonPath("$.totalElementos").value(2));

        // De outra pessoa: 404 em tudo, e o histórico dela não mostra
        mvc.perform(get("/api/ocorrencias/{id}", primeira).header(HttpHeaders.AUTHORIZATION, outra))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensagem").value("Ocorrência não encontrada."));
        mvc.perform(delete("/api/ocorrencias/{id}", primeira).header(HttpHeaders.AUTHORIZATION, outra))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ocorrencias/minhas").header(HttpHeaders.AUTHORIZATION, outra))
                .andExpect(jsonPath("$.totalElementos").value(0));

        // Remover do histórico
        esperarResumo(dona, primeira);
        mvc.perform(delete("/api/ocorrencias/{id}", primeira).header(HttpHeaders.AUTHORIZATION, dona))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/ocorrencias/{id}", primeira).header(HttpHeaders.AUTHORIZATION, dona))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ocorrencias/minhas").header(HttpHeaders.AUTHORIZATION, dona))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void atualizarRecalculaAUrgenciaEGeraUmResumoNovo() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);
        long hospital = unidadeRepository.findByNome("Hospital Exemplo Centro").orElseThrow().getId();
        Instant horaPicada = haMinutos(40);
        String criada = registrar(token, corpo("SERPENTE", "ADULTO", "\"DOR_LOCAL\"", horaPicada, null))
                .andExpect(jsonPath("$.urgencia").value("ALTA"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(criada, "$.id")).longValue();
        String criadoEm = JsonPath.read(criada, "$.criadoEm");
        esperarResumo(token, id);

        // Apareceu sangramento e a pessoa escolheu a unidade: a urgência sobe e o resumo é refeito
        atualizar(token, id, corpo("SERPENTE", "ADULTO", "\"DOR_LOCAL\",\"SANGRAMENTO\"", horaPicada, hospital))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.sintomas", contains("DOR_LOCAL", "SANGRAMENTO")))
                .andExpect(jsonPath("$.urgencia").value("EMERGENCIA"))
                .andExpect(jsonPath("$.motivos", contains("Sinal de alerta", "Animal com risco de agravamento")))
                .andExpect(jsonPath("$.ligar192").value(true))
                .andExpect(jsonPath("$.unidadeNome").value("Hospital Exemplo Centro"))
                .andExpect(jsonPath("$.statusResumo").value("PENDENTE"))
                .andExpect(jsonPath("$.resumo").isEmpty())
                .andExpect(jsonPath("$.criadoEm").value(criadoEm));

        String refeita = esperarResumo(token, id);
        assertThat((String) JsonPath.read(refeita, "$.resumo"))
                .contains("Sangramento ou urina escura (ALERTA)")
                .contains("Urgência: EMERGÊNCIA")
                .contains("Destino: Hospital Exemplo Centro");
    }

    @Test
    void atualizarSoODonoComAsMesmasRegrasESemResumoPendente() throws Exception {
        String dona = ApoioDeTeste.cadastrarEEntrar(mvc);
        String outra = ApoioDeTeste.cadastrarEEntrar(mvc);
        long id = registrarERetornarId(dona);
        esperarResumo(dona, id);
        String valido = corpo("ABELHA", "ADULTO", "", haMinutos(5), null);

        atualizar(outra, id, valido)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensagem").value("Ocorrência não encontrada."));
        mvc.perform(put("/api/ocorrencias/{id}", id).contentType(MediaType.APPLICATION_JSON).content(valido))
                .andExpect(status().isUnauthorized());
        atualizar(dona, id, corpo("ABELHA", "ADULTO", "", haMinutos(73 * 60), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("horaPicada"));
        atualizar(dona, id, corpo("ABELHA", "ADULTO", "", haMinutos(5), 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensagem").value("Unidade não encontrada."));
        atualizar(dona, id, "{\"sintomas\":[]}")
                .andExpect(status().isBadRequest());

        // Com o resumo anterior ainda sendo gerado, o novo poderia ficar por baixo: 409
        var ocorrencia = ocorrenciaRepository.findById(id).orElseThrow();
        ocorrencia.setStatusResumo(StatusResumo.PENDENTE);
        ocorrenciaRepository.save(ocorrencia);
        atualizar(dona, id, valido)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value("O resumo ainda está sendo gerado. Tente de novo em alguns segundos."));
    }

    @Test
    void semLoginE401() throws Exception {
        mvc.perform(get("/api/ocorrencias/minhas")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ocorrencias").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("ESCORPIAO", "ADULTO", "", haMinutos(5), null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cidadaoDeTesteJaTemUmaOcorrenciaComResumo() throws Exception {
        String resposta = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cidadao@sospeconha.dev\",\"senha\":\"Teste@123\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + JsonPath.read(resposta, "$.token");

        mvc.perform(get("/api/ocorrencias/minhas").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].statusResumo").value("MODELO_PADRAO"))
                .andExpect(jsonPath("$.conteudo[0].unidadeNome").value("Hospital Exemplo Paulista"))
                .andExpect(jsonPath("$.conteudo[0].urgencia").value("MODERADA"));
    }
}
