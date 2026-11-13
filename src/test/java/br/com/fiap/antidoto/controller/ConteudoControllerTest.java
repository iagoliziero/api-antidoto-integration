package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.model.TipoAnimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Primeiros socorros (MVP-02 §6): público, um conteúdo por animal, nunca dose ou remédio. */
@SpringBootTest
@AutoConfigureMockMvc
class ConteudoControllerTest {

    private static final String NAO_CAPTURAR = "Não tente capturar o animal. Se for seguro, tire uma foto de longe.";
    private static final Pattern PROIBIDO = Pattern.compile("\\b(doses?|mg|ml|ampolas?|medicamentos?|soro)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Autowired
    private MockMvc mvc;

    @Test
    void cadaAnimalTemSeuConteudoSemLogin() throws Exception {
        for (TipoAnimal animal : TipoAnimal.values()) {
            String json = mvc.perform(get("/api/conteudo/primeiros-socorros/{tipoAnimal}", animal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tipoAnimal").value(animal.name()))
                    .andExpect(jsonPath("$.faca", hasSize(5)))
                    .andExpect(jsonPath("$.naoFaca", hasSize(6)))
                    .andExpect(jsonPath("$.atencao", hasItem(NAO_CAPTURAR)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(PROIBIDO.matcher(json).find()).as("dose ou remédio em %s", animal).isFalse();
        }
    }

    @Test
    void escorpiaoEAbelhaTemOsCuidadosDoAnimal() throws Exception {
        mvc.perform(get("/api/conteudo/primeiros-socorros/ESCORPIAO"))
                .andExpect(jsonPath("$.atencao[0]").value("Compressa morna pode aliviar a dor no caminho."))
                .andExpect(jsonPath("$.atencao", hasSize(4)));
        mvc.perform(get("/api/conteudo/primeiros-socorros/ABELHA"))
                .andExpect(jsonPath("$.atencao[0]").value("Tire os ferrões raspando com um cartão, sem pinça."));
    }

    @Test
    void animalQueNaoExisteE400() throws Exception {
        mvc.perform(get("/api/conteudo/primeiros-socorros/DRAGAO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("tipoAnimal"));
    }
}
