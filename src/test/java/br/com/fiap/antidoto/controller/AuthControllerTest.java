package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.ApoioDeTeste;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cadastro, login, /api/usuarios/me e a troca de senha de ponta a ponta (H2 + Spring Security + JWT). */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions postar(String rota, String json) throws Exception {
        return mvc.perform(post(rota).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void cadastroCriaUmCidadaoEJaDevolveOToken() throws Exception {
        String email = ApoioDeTeste.emailNovo();

        postar("/api/auth/cadastro", """
                {"nome":"  Maria Lima ","email":"  %s ","senha":"Teste123","telefone":"11988887777"}
                """.formatted(email.toUpperCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(not(emptyString())))
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.expiraEmSegundos").value(86400))
                .andExpect(jsonPath("$.usuario.id").isNumber())
                .andExpect(jsonPath("$.usuario.nome").value("Maria Lima"))
                .andExpect(jsonPath("$.usuario.email").value(email))
                .andExpect(jsonPath("$.usuario.telefone").value("11988887777"))
                .andExpect(jsonPath("$.usuario.perfil").value("CIDADAO"))
                .andExpect(jsonPath("$.usuario.criadoEm").exists())
                .andExpect(jsonPath("$.usuario.senhaHash").doesNotExist());
    }

    @Test
    void emailJaCadastradoDa409() throws Exception {
        postar("/api/auth/cadastro", """
                {"nome":"Outra Ana","email":"cidadao@sospeconha.dev","senha":"Teste123"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value("E-mail já cadastrado."));
    }

    @Test
    void cadastroInvalidoApontaCadaCampo() throws Exception {
        postar("/api/auth/cadastro", """
                {"nome":"A","email":"nao-e-email","senha":"semnumero","telefone":"123"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo == 'nome')].mensagem")
                        .value("Informe o nome (pelo menos 2 letras)"))
                .andExpect(jsonPath("$.campos[?(@.campo == 'email')].mensagem").value("E-mail inválido"))
                .andExpect(jsonPath("$.campos[?(@.campo == 'senha')].mensagem")
                        .value("A senha precisa ter pelo menos uma letra e um número"))
                .andExpect(jsonPath("$.campos[?(@.campo == 'telefone')].mensagem")
                        .value("Telefone inválido: use o DDD e o número"));
    }

    @Test
    void loginDoUsuarioDeTesteIgnoraMaiusculasEEspacos() throws Exception {
        postar("/api/auth/login", """
                {"email":" CIDADAO@sospeconha.dev ","senha":"Teste@123"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(not(emptyString())))
                .andExpect(jsonPath("$.usuario.nome").value("Ana Souza"))
                .andExpect(jsonPath("$.usuario.perfil").value("CIDADAO"));
    }

    @Test
    void senhaErradaEEmailInexistenteDaoOMesmo401() throws Exception {
        postar("/api/auth/login", """
                {"email":"cidadao@sospeconha.dev","senha":"errada123"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").value("Não autenticado"))
                .andExpect(jsonPath("$.mensagem").value("E-mail ou senha incorretos"));

        postar("/api/auth/login", """
                {"email":"ninguem@antidoto.dev","senha":"Teste@123"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensagem").value("E-mail ou senha incorretos"));
    }

    @Test
    void loginFuncionaMesmoComUmTokenVencidoNoCabecalho() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-vencido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cidadao@sospeconha.dev\",\"senha\":\"Teste@123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void meuPerfilSemTokenOuComTokenInvalidoDa401NoFormatoDoContrato() throws Exception {
        mvc.perform(get("/api/usuarios/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.erro").value("Não autenticado"))
                .andExpect(jsonPath("$.mensagem").value("Faça login para continuar."))
                .andExpect(jsonPath("$.caminho").value("/api/usuarios/me"));

        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensagem").value("Sessão expirada ou inválida. Entre novamente."));
    }

    @Test
    void meuPerfilComTokenEditarNomeETelefone() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);

        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Pessoa de Teste"))
                .andExpect(jsonPath("$.telefone").isEmpty());

        mvc.perform(put("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Pessoa Editada\",\"telefone\":\"21988887777\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Pessoa Editada"))
                .andExpect(jsonPath("$.telefone").value("21988887777"));

        // Telefone vazio apaga; o e-mail continua o mesmo
        mvc.perform(put("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Pessoa Editada\",\"telefone\":\"\",\"email\":\"outro@x.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefone").isEmpty())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.startsWith("teste-")));

        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.nome").value("Pessoa Editada"));
    }

    private org.springframework.test.web.servlet.ResultActions trocarSenha(String token, String json) throws Exception {
        return mvc.perform(put("/api/usuarios/me/senha").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void alterarSenhaConfereAAtualEDepoisSoANovaEntra() throws Exception {
        String email = ApoioDeTeste.emailNovo();
        String cadastro = postar("/api/auth/cadastro", """
                {"nome":"Pessoa de Teste","email":"%s","senha":"Teste123","telefone":null}
                """.formatted(email))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + JsonPath.read(cadastro, "$.token");

        // Senha atual errada: 400 no campo, não 401 (o 401 faria o app encerrar a sessão)
        trocarSenha(token, "{\"senhaAtual\":\"Errada123\",\"novaSenha\":\"NovaSenha1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("senhaAtual"))
                .andExpect(jsonPath("$.campos[0].mensagem").value("Senha atual incorreta."));
        // A nova segue a regra do cadastro e precisa ser diferente da atual
        trocarSenha(token, "{\"senhaAtual\":\"Teste123\",\"novaSenha\":\"curta\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo == 'novaSenha')]").exists());
        trocarSenha(token, "{\"senhaAtual\":\"Teste123\",\"novaSenha\":\"Teste123\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].mensagem").value("A nova senha precisa ser diferente da atual."));
        mvc.perform(put("/api/usuarios/me/senha").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\":\"Teste123\",\"novaSenha\":\"NovaSenha1\"}"))
                .andExpect(status().isUnauthorized());

        trocarSenha(token, "{\"senhaAtual\":\"Teste123\",\"novaSenha\":\"NovaSenha1\"}")
                .andExpect(status().isNoContent());

        postar("/api/auth/login", "{\"email\":\"%s\",\"senha\":\"Teste123\"}".formatted(email))
                .andExpect(status().isUnauthorized());
        postar("/api/auth/login", "{\"email\":\"%s\",\"senha\":\"NovaSenha1\"}".formatted(email))
                .andExpect(status().isOk());
    }

    @Test
    void editarPerfilInvalidoApontaOsCampos() throws Exception {
        String token = ApoioDeTeste.cadastrarEEntrar(mvc);

        mvc.perform(put("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"A\",\"telefone\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo == 'nome')]").exists())
                .andExpect(jsonPath("$.campos[?(@.campo == 'telefone')]").exists());
    }

    @Test
    void corsSoParaAsOrigensConfiguradas() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8081"));

        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://site-qualquer.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
