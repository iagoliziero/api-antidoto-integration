# Antídoto API — Contrato de Desenvolvimento

API Java (Spring Boot) que o app **Antídoto** consome. **Não é a entrega da disciplina de Java**: existe para o app funcionar de verdade. Todo código gerado (por humano ou IA) segue estas regras, que descrevem o **estado real do código**. O que ainda não existe aparece com o passo em que entra — **(Passo N)**.

- **Fonte da verdade:** `docs/api/openapi-mvp.yaml` (rotas, campos, enums, erros). É **o mesmo arquivo do app**. Se o MVP-01 e o app discordarem, **vale o app**, e o Java se ajusta.
- **Regras de negócio:** `docs/MVP-00-LEIA-ME.md` (RN-U, RN-S, RN-G) e `docs/MVP-01-BACKEND-JAVA.md`.
- **Padrão:** o mesmo do Java do Challenge (java-afetto): Gradle, pacotes por camada, DTOs `record`, mapper `@Component`, injeção por construtor, getters e setters escritos (sem Lombok).
- **Idioma:** código, mensagens e comentários em português. Nomes reservados do framework ficam como são.

---

## 1. Stack

| Item       | Escolha                                                                                         |
| ---------- | ----------------------------------------------------------------------------------------------- |
| Java       | 21                                                                                              |
| Framework  | Spring Boot 4.0 (`webmvc`, `validation`, `data-jpa`, `flyway`, `actuator`)                      |
| Banco      | H2 em memória (modo PostgreSQL) no computador e nos testes; PostgreSQL no Render (`DB_URL`)     |
| Migração   | Flyway, `db/migration/V*__*.sql`, em SQL do PostgreSQL. `ddl-auto=validate`: nunca `update`     |
| Docs       | springdoc-openapi 3 → `/swagger-ui.html`                                                        |
| JSON       | Jackson 3 (`tools.jackson`), padrão do Boot 4. Datas `Instant` em ISO-8601 UTC                  |
| Segurança  | Spring Security + JWT (JJWT 0.12, HS256, 24 h), BCrypt                                          |
| IA         | Spring AI 2.0 (BOM + `spring-ai-starter-model-openai`): o cliente OpenAI apontando para o Groq  |
| Testes     | JUnit 5, AssertJ, MockMvc (`@SpringBootTest` + `@AutoConfigureMockMvc` ou `standaloneSetup`)   |
| Build      | Gradle wrapper (`./gradlew test`, `./gradlew bootJar`)                                          |

Biblioteca nova: só combinando antes.

---

## 2. Estrutura

```
br.com.fiap.antidoto
├── AntidotoApiApplication.java
├── config/       RelogioConfig (bean Clock), OpenApiConfig (Bearer no Swagger), AssincronoConfig (@EnableAsync), DadosIniciais (usuários, as 12 unidades fictícias, 3 avistamentos e 1 ocorrência do cidadão), BancoDoRender (DATABASE_URL → JDBC, em META-INF/spring.factories)
├── controller/   AuthController, UsuarioController, UnidadeController, AvistamentoController, OcorrenciaController
├── dto/          PaginaResponse; erro/ (ErroResponse, CampoErroResponse); usuario/ (CadastroRequest, LoginRequest, AtualizarUsuarioRequest, UsuarioResponse, AuthResponse); unidade/ (UnidadeProximaResponse, UnidadeResponse); avistamento/ (AvistamentoRequest, AvistamentoResponse); ocorrencia/ (OcorrenciaRequest, OcorrenciaResponse, ResultadoUrgencia)
├── ia/           ResumoIaService (Spring AI → Groq, com tempo limite e conferência da resposta), ResumoModeloPadrao (modelo de texto e os dadosDaTriagem que vão para a IA)
├── mensageria/   OcorrenciaCriadaEvento, ResumoOcorrenciaConsumer (@Async depois do commit; sem RabbitMQ)
├── exception/    TratadorGlobalDeErros, RecursoNaoEncontradoException, RegraDeNegocioException, ConflitoException, CredenciaisInvalidasException
├── mapper/       UsuarioMapper, UnidadeMapper, AvistamentoMapper, OcorrenciaMapper (entidade → DTO; avisos RN-G2/RN-G7)
├── model/        Usuario, UnidadeSaude, Ocorrencia, Avistamento + enums (rótulos iguais aos do app; Urgencia tem maior()/proxima(); Sintoma tem texto e alerta)
├── repository/   interfaces JpaRepository
├── security/     SecurityConfig, JwtService, JwtFiltro, RespostasDeSeguranca, UsuarioAutenticado
└── service/      AuthService, UsuarioService, UnidadeService, CompatibilidadeSoroService (RN-S), DistanciaService (Haversine), AvistamentoService, UrgenciaService (RN-U), OcorrenciaService
```

---

## 3. Camadas

- **Controller:** recebe, valida com `@Valid`, chama o service e devolve o DTO com o status do contrato (201 no POST, 204 no DELETE). **Nenhuma regra de negócio.**
- **Service:** toda regra. `@Transactional` nas escritas e `@Transactional(readOnly = true)` nas leituras; converte para DTO **dentro** da transação (`open-in-view=false`).
- **Entidade nunca sai do service.** A resposta é sempre um `record` com **exatamente** os nomes do YAML.
- **Mapper:** `@Component` com a conversão entidade → DTO (como no Afetto).
- **Repository:** Spring Data JPA; consulta derivada do nome do método antes de `@Query`.
- **Injeção sempre por construtor.**
- **Tempo:** `Instant.now(relogio)` com o `Clock` injetado, nunca `Instant.now()` solto (os testes fixam o relógio).

---

## 4. Erros (MVP-01 §7)

Todo erro sai no formato do contrato, montado pelo `TratadorGlobalDeErros`:

```json
{ "status": 400, "erro": "Dados inválidos", "mensagem": "Um ou mais campos são inválidos.",
  "campos": [{ "campo": "email", "mensagem": "E-mail inválido" }],
  "caminho": "/api/auth/cadastro", "timestamp": "2026-11-10T22:15:00Z" }
```

| Situação                                                              | Como                                                 |
| --------------------------------------------------------------------- | ---------------------------------------------------- |
| `@Valid` no corpo, parâmetro da URL inválido, enum desconhecido       | 400 com `campos` (nome do campo no JSON)             |
| Regra que o Bean Validation não cobre (RN-G4, `dataHora` no futuro)   | `throw new RegraDeNegocioException("campo", "...")`  |
| Não existe **ou é de outro usuário**                                  | `RecursoNaoEncontradoException` → 404 (nunca 403)   |
| E-mail já cadastrado                                                  | `ConflitoException("E-mail já cadastrado.")` → 409   |
| Qualquer outra                                                        | 500 genérico; o detalhe só no log                    |

- `campos` só aparece quando há campo inválido.
- O nome em `campos` é o do JSON do contrato: o app usa para pôr a mensagem embaixo do campo certo.
- Mensagens em português, prontas para a tela.

---

## 5. Segurança (MVP-01 §5)

- Stateless, CSRF desligado, sem login por formulário. O `JwtFiltro` lê o `Bearer`, confere o token e **confere no banco** que o usuário ainda existe; o perfil vira o papel (`ROLE_CIDADAO`, `ROLE_GESTOR`).
- **Públicas:** `POST /api/auth/**`, `GET /api/unidades`, `/api/unidades/proximas`, `/api/unidades/{id}` (o SOS funciona sem login, RN-G1), `/actuator/health`, Swagger. **O resto exige login.**
- Token inválido ou vencido **não barra** rota pública: o app logado com o token vencido continua vendo as unidades no SOS.
- 401 e 403 saem no formato do contrato (`RespostasDeSeguranca`): sem token, "Faça login para continuar."; com token ruim, "Sessão expirada ou inválida. Entre novamente."
- Login errado: 401 "E-mail ou senha incorretos", igual para e-mail inexistente e senha errada.
- Quem está logado: `@AuthenticationPrincipal UsuarioAutenticado` (id, email, perfil). **Nunca** confiar num id de usuário que venha no corpo.
- E-mail sempre `trim().toLowerCase()` (no construtor do record); telefone vazio vira `null`.
- `JWT_SECRET` em base64 com 32+ bytes; sem ele, segredo aleatório por subida (só serve no computador).
- CORS só para `CORS_ORIGENS` (o app no celular não usa CORS). Nunca `*`.

## 6. Unidades próximas (RN-S, MVP-00 §4.4)

- `CompatibilidadeSoroService` é a única dona da tabela animal → soro: `atende(unidade, animal)` e `sorosCompativeis(unidade, animal)` (RN-S3, na ordem do enum; vazio na abelha).
- `NAO_SEI`: precisa de ANTIBOTROPICO **e** (ANTIESCORPIONICO **ou** ANTIARACNIDICO). `ABELHA`: qualquer HOSPITAL ou UPA, sem soro.
- `UnidadeService.buscarProximas`: carrega as ativas (RN-S1), filtra, calcula a distância (`DistanciaService`, Haversine com raio de 6371 km), ordena da mais perto (RN-S2; empate pelo id), corta no `limite` e arredonda o `distanciaKm` para 1 casa. Não há raio máximo. Lista vazia é 200 `[]` (RN-S4).
- Parâmetros validados na URL (`@DecimalMin`/`@DecimalMax`, `@Min`/`@Max`): latitude −34 a 6, longitude −74 a −34, limite 1 a 10.

## 7. Recursos do usuário (avistamentos e ocorrências)

- **Só o dono (RN-G5):** toda busca passa pelo id do usuário logado (`findByIdAndUsuarioId`). De outro usuário ou inexistente: `RecursoNaoEncontradoException` → **404**, nunca 403.
- O dono vem do token (`@AuthenticationPrincipal`), nunca do corpo; na criação, `usuarioRepository.getReferenceById(id)`.
- Listas "meus/minhas": `criadoEm` desc e, no empate, `id` desc; paginadas no `PaginaResponse`.
- `dataHora` no futuro além de 5 min (relógio do celular adiantado): `RegraDeNegocioException("dataHora", ...)`. O app manda a `dataHora` original no PUT; o PUT atualiza o `atualizadoEm`. DELETE é físico (204).
- Observação em branco vira `null` (construtor do record). Erro de latitude ou longitude usa a mensagem do app ("A localização precisa ser no Brasil"); como o app chama o campo de `localizacao`, ela aparece como mensagem geral.

## 8. Ocorrências e resumo (MVP-01 §6.3, §8 e §9)

- **RN-U:** `UrgenciaService` é a cópia exata do `utils/urgencia.ts` do app (mesmos motivos, mesma ordem, os mesmos 10 casos em `UrgenciaServiceTest`). Mudou um? Muda o outro, combinando antes. A IA **nunca** decide a urgência.
- **RN-G4:** hora da picada entre agora (+5 min) e 72 h atrás; fora disso, `RegraDeNegocioException("horaPicada", ...)`. Unidade de destino inexistente ou desativada: 404.
- **Fluxo do resumo:** o `POST` salva com `PENDENTE` e publica `OcorrenciaCriadaEvento`; o `ResumoOcorrenciaConsumer` roda **depois do commit**, em outra thread (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`), e é idempotente (só mexe se ainda estiver `PENDENTE`). Sem RabbitMQ: é o modo `@Async` do MVP-01 §9.
- **Resumo:** com `antidoto.ia.habilitada=true` (`IA_HABILITADA`), o consumer tenta o `ResumoIaService`: deu certo → texto da IA e `PRONTO`. Desligada ou falhou → `ResumoModeloPadrao` e `MODELO_PADRAO`. A ocorrência nunca fica sem resumo.
- **O que vai para a IA:** só `"Dados da ocorrência:\n" + ResumoModeloPadrao.dadosDaTriagem(...)` (animal, faixa etária, hora e tempo, sintomas com ALERTA, urgência e motivos, nome da unidade). Nada de nome, e-mail, telefone ou coordenadas (RN-G6). As instruções ficam em `ResumoIaService.INSTRUCOES` (system prompt).
- **Quando a IA não vale:** qualquer exceção (rede, 401, 429, 5xx), mais de `antidoto.ia.tempo-limite-em-segundos` (6 s; `orTimeout`), resposta vazia, com mais de 1200 caracteres ou com dose/mg/ml/ampola/medicamento (`ResumoIaService.validar`). Sem novas tentativas (`max-retries=0`): o app consulta a cada 3 s e o modelo padrão é a reserva.
- **Modelo:** `openai/gpt-oss-120b` (`IA_MODELO`), `reasoning-effort=low`, `temperature=0.2`, `max-completion-tokens=800`. O `llama-3.3-70b-versatile` do MVP-01 saiu do plano grátis do Groq em 16/08/2026. O starter liga só o chat (`spring.ai.model.*=none` nos outros).
- **Chave:** só `GROQ_API_KEY` (variável de ambiente; no Render, `sync: false`). Nunca no Git, no `application.properties` ou no app. Sem ela, o padrão `desligado` deixa a API subir.
- Hora em Brasília, nunca dose ou remédio. **Não logar o texto do resumo nem os dados da triagem** (só id, status, tempo e o nome da exceção).
- **Testes:** `ResumoIaServiceTest` (a conferência) e `ResumoIaIntegracaoTest` (um servidor falso no lugar do Groq: o que o Spring AI manda, `PRONTO`, dose, lentidão e erro → `MODELO_PADRAO`). Nenhum teste chama o Groq de verdade.
- A resposta sempre traz `ligar192` (EMERGENCIA), `aviso` (RN-G2) e `avisoResumo` (RN-G7), as constantes do `OcorrenciaMapper`.

## 9. Deploy (Render)

- `render.yaml` (Blueprint): Postgres `free` + web service Docker `free`, os dois em `virginia`; `healthCheckPath: /actuator/health`; deploy a cada commit.
- Variáveis no Render: `DATABASE_URL` (do banco, `postgresql://...`), `JWT_SECRET` (`generateValue`, base64 de 256 bits), `CORS_ORIGENS`, `CARREGAR_DADOS_INICIAIS`, `GROQ_API_KEY` (`sync: false`: o Render pede o valor só na criação do Blueprint; num Blueprint que já existe, cadastrar à mão em Environment), `IA_HABILITADA`, `IA_MODELO`. O Render define a `PORT`.
- `BancoDoRender` (EnvironmentPostProcessor) converte a `DATABASE_URL` em `spring.datasource.*`; `DB_URL` manual tem prioridade.
- Plano grátis: 512 MB e 0,1 de CPU → JVM com `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`. A API dorme após 15 min sem tráfego: um monitor (UptimeRobot, 5 min) no `/actuator/health` mantém acordada.
- Postgres grátis expira em 30 dias: serve para esta API (que não é a entrega), não para guardar dados.

## 10. Banco

- O schema é do Flyway (`V1__criar_tabelas.sql`). Mudou uma entidade? **Nova migração** `V2__...`; nunca editar uma migração que já rodou no Render.
- SQL do PostgreSQL que também roda no H2 em modo PostgreSQL: `BIGINT ... GENERATED BY DEFAULT AS IDENTITY`, `VARCHAR`, `BOOLEAN`, `DOUBLE PRECISION` (coordenadas), `TIMESTAMP(6) WITH TIME ZONE` (datas `Instant`).
- Enums gravados como texto (`@Enumerated(EnumType.STRING)`), com `CHECK` no banco.
- `Ocorrencia.motivos` é texto separado por `;`; na resposta vira lista.

---

## 11. Paginação

Parâmetros `pagina` (padrão 0) e `tamanho` (padrão 20, máximo 50). Resposta **sempre** `PaginaResponse<T>` (`conteudo`, `pagina`, `tamanho`, `totalElementos`, `totalPaginas`), nunca o `Page` do Spring.

---

## 12. O que nunca fazer

- Responder diferente do `openapi-mvp.yaml` (rota, campo, enum, status).
- Devolver entidade JPA ou `Page` do Spring.
- `ddl-auto=update/create`; editar migração que já rodou.
- Regra de negócio no controller; `@Autowired` em campo; `System.out.println`; `catch` vazio.
- 403 para recurso de outro usuário (é 404).
- Senha, token, segredo ou a chave da IA no código ou no log; commitar o `.env`.
- Mandar nome, e-mail, telefone ou coordenadas para a IA; falar de dose ou remédio no resumo.
- Deixar a ocorrência falhar por causa do resumo: sempre há o modelo padrão.

---

## 13. Checklist antes de todo commit

```
[ ] ./gradlew test passando
[ ] Resposta igual ao openapi-mvp.yaml (nomes, status, formato de erro)
[ ] Regra no service, DTO record na resposta, injeção por construtor
[ ] Nenhum segredo no código; .env fora do Git
[ ] Migração nova em vez de editar a antiga
```

---

_Antídoto — Global Solution 2026/2 · FIAP_
