# Antídoto — API Java

API REST que o app **Antídoto** consome (Global Solution 2026/2 · FIAP · CitizenTech).

> **Esta API é só para o app.** Ela existe para o app funcionar de verdade e para testar a
> integração. **Não é a entrega da disciplina de Java**: essa é feita à parte, sobre o mesmo
> contrato. Por isso as duas são trocáveis no app, só mudando o `EXPO_PUBLIC_API_URL`.

- **Contrato:** [`docs/api/openapi-mvp.yaml`](docs/api/openapi-mvp.yaml), o mesmo do app. Responder diferente dele quebra o app.
- **Regras:** [`docs/MVP-00-LEIA-ME.md`](docs/MVP-00-LEIA-ME.md) (urgência, soros, regras gerais) e [`docs/MVP-01-BACKEND-JAVA.md`](docs/MVP-01-BACKEND-JAVA.md).
- **Os dados de exemplo são fictícios.**

---

## 🚧 Andamento

| Passo | O que entra                                                                                | Situação |
| ----- | ------------------------------------------------------------------------------------------ | -------- |
| 1     | Base: Spring Boot, banco com Flyway, entidades, formato de erro, paginação, Swagger, Docker | ✅       |
| 2     | Login e cadastro (JWT), rotas protegidas, `/api/usuarios/me`, usuários de teste            | ✅       |
| 3     | Unidades próximas com soro compatível (RN-S) e 12 unidades fictícias                       | ✅       |
| 4     | CRUD de avistamentos (só o dono)                                                           | ✅       |
| 5     | Ocorrências: urgência (RN-U) e resumo de pré-atendimento em segundo plano                  | ✅       |
| 6     | Deploy no Render (Postgres) e o app apontando para a API                                   | ✅       |
| 7     | (Opcional) Resumo com IA de verdade (Spring AI + Groq), com o modelo padrão de reserva     | ✅       |
| 7.1   | Ajustes da IA depois do teste no Render: aquecimento, instruções mais rígidas e conferência | ✅       |
| 8     | Primeiros socorros pela API e atualizar a ocorrência (o CRUD completo no app)               | ✅       |

**No ar:** https://api-antidoto-integration.onrender.com (Swagger em `/swagger-ui.html`).

---

## 🛠️ Tecnologias

Java 21 · Spring Boot 4 (Web MVC, Validation, Data JPA, Security, Actuator) · JWT (JJWT) · Flyway · H2 (computador e testes) ·
PostgreSQL (Render) · Spring AI (cliente OpenAI apontando para o Groq) · springdoc-openapi (Swagger) · JUnit 5 + MockMvc ·
Gradle (wrapper) · Docker.

---

## ▶️ Como rodar

Pré-requisito: **JDK 21**. O Gradle vem pelo wrapper; não precisa instalar.

```bash
./gradlew bootRun          # Windows: gradlew.bat bootRun
```

- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html
- Saúde: http://localhost:8080/actuator/health

Sem nenhuma variável, a API usa o **H2 em memória** (os dados somem ao parar). O Flyway cria as
tabelas na subida, e os usuários de teste são criados em seguida.

### Usuários de teste (fictícios)

| E-mail                   | Senha       | Perfil  |
| ------------------------ | ----------- | ------- |
| `cidadao@sospeconha.dev` | `Teste@123` | CIDADAO (com 3 avistamentos e 1 ocorrência de exemplo, perto da Paulista) |
| `gestor@sospeconha.dev`  | `Teste@123` | GESTOR  |

Os mesmos do MVP-01, para o app funcionar igual com esta API e com a do grupo. No Swagger, faça o
login, copie o `token` e cole em **Authorize**.

### Resumo de pré-atendimento

Ao registrar uma ocorrência, a API responde na hora com o resumo `PENDENTE` e, depois do commit,
gera o resumo **em segundo plano** (`@Async`). Ele usa só os dados da triagem: nada de nome, e-mail,
telefone ou coordenadas (RN-G6).

- **Com a IA ligada** (`IA_HABILITADA=true` e a `GROQ_API_KEY`): o texto vem do Groq e o resumo fica
  `PRONTO`.
- **Sem a IA, ou se ela falhar** (erro, chave inválida, mais de 6 s, ou uma resposta que não passa
  na conferência): vale o modelo de texto, com o status `MODELO_PADRAO`. A ocorrência nunca fica sem
  resumo.

O modelo de texto:

```
PRÉ-ATENDIMENTO — Antídoto
Animal: Escorpião · Faixa etária: criança
Picada: 22:15 (há 25 min)
Sintomas: Vômitos repetidos (ALERTA)
Urgência: EMERGÊNCIA — Sinal de alerta; Escorpião em criança
Destino: Hospital Exemplo Centro
```

### IA do resumo (Groq)

A API fala com o Groq pelo **Spring AI**, com o cliente da OpenAI apontando para
`https://api.groq.com/openai/v1` (o Groq aceita a mesma API). O modelo é o `openai/gpt-oss-120b`,
com raciocínio baixo, temperatura 0,2 e até 800 tokens. As instruções (em
`ResumoIaService.INSTRUCOES`) são as do MVP-01 com regras a mais: usar só os dados enviados, copiar a
urgência, os motivos e o destino como vieram, chamar de alerta só o sintoma marcado com `(ALERTA)`,
uma informação por linha e nunca sugerir tratamento, remédio ou dose. A chave **só existe na API**
(variável de ambiente): o app nunca a vê.

**Conferência da resposta** (`ResumoIaService.validar`): a resposta é descartada se vier vazia, com
mais de 1.200 caracteres, falando de dose, mg, ml, ampola ou medicamento, com outro nível de urgência
(a IA nunca decide a urgência), falando em alerta sem sintoma de alerta ou com um campo vazio
(`Destino:` sem nada).

**Aquecimento:** no plano grátis do Render (0,1 de CPU), a primeira chamada depois da subida carrega
o cliente HTTP e passava dos 6 s. Por isso, logo depois de subir, a API faz uma chamada mínima ao Groq
(log `IA pronta (aquecimento) em ... ms`) e a primeira ocorrência já encontra tudo pronto.

1. Crie uma conta grátis no [GroqCloud](https://console.groq.com) e uma chave em
   [API Keys](https://console.groq.com/keys) (começa com `gsk_`). Copie e guarde num lugar seguro;
   não cole em nenhum arquivo do projeto.
2. No computador:

   ```bash
   GROQ_API_KEY=gsk_... IA_HABILITADA=true ./gradlew bootRun
   ```

   No Windows (PowerShell): `$env:GROQ_API_KEY="gsk_..."; $env:IA_HABILITADA="true"; .\gradlew.bat bootRun`.
3. Registre uma ocorrência (no app ou no Swagger) e veja o log: `Resumo da ocorrência N: PRONTO em ... ms`.
   Com `MODELO_PADRAO`, a linha de cima diz o motivo: `IA indisponível ...` (erro, chave inválida ou
   tempo esgotado) ou `resposta descartada` (não passou na conferência). O log nunca mostra os dados
   da triagem nem o texto do resumo. Busque por `ResumoIaService` (sem acento) no log do Render.

No Render, a chave vai em **Environment** (veja o deploy). Os limites do plano grátis ficam em
[console.groq.com/settings/limits](https://console.groq.com/settings/limits).

### Unidades de saúde (fictícias)

São 12, espalhadas por São Paulo (Sé, Paulista, Consolação, Pinheiros, Butantã, Lapa, Santana,
Tatuapé, Vila Mariana, Ipiranga, Jabaquara e Santo Amaro), com combinações de soros que cobrem
todas as linhas da RN-S: uma UPA sem soro, duas UBS (que não entram na abelha) e três hospitais que
atendem o "Não sei" (ANTIBOTRÓPICO + ESCORPIÔNICO ou ARACNÍDICO). Nomes, endereços e telefones são
inventados ("Hospital Exemplo Centro", "Rua Exemplo, 100"). Para testar perto delas no emulador,
use a localização da Avenida Paulista (-23.5614, -46.6559).

### Rotas prontas

| Rota                         | Acesso  | O que faz                                    |
| ---------------------------- | ------- | -------------------------------------------- |
| `POST /api/auth/cadastro`    | público | Cria um CIDADAO e já devolve o token (201)   |
| `POST /api/auth/login`       | público | Login (200); errado: 401 "E-mail ou senha incorretos" |
| `GET /api/usuarios/me`       | logado  | Dados de quem está logado                    |
| `PUT /api/usuarios/me`       | logado  | Muda só o nome e o telefone                  |
| `GET /api/unidades/proximas` | público | Unidades ativas com soro compatível com o animal (RN-S), da mais perto para a mais longe (`latitude`, `longitude`, `tipoAnimal`, `limite` de 1 a 10, padrão 5) |
| `GET /api/unidades`          | público | Unidades ativas por nome, paginadas          |
| `GET /api/unidades/{id}`     | público | Detalhe de uma unidade ativa                 |
| `POST /api/avistamentos`     | logado  | Registra um avistamento (201); `dataHora` sem futuro (tolerância de 5 min) |
| `GET /api/avistamentos/meus` | logado  | Os do usuário, mais recentes primeiro, paginados (`tamanho` até 50) |
| `GET /api/avistamentos/{id}` | logado  | Detalhe; de outro usuário, 404 (RN-G5)       |
| `PUT /api/avistamentos/{id}` | logado  | Edita; atualiza o `atualizadoEm`             |
| `DELETE /api/avistamentos/{id}` | logado | Exclui (204)                                |
| `POST /api/ocorrencias`      | logado  | Registra a picada: calcula a urgência (RN-U) e responde 201 com o resumo `PENDENTE`; hora da picada entre agora e 72 h atrás (RN-G4) |
| `GET /api/ocorrencias/minhas` | logado | Histórico, mais recentes primeiro, paginado  |
| `GET /api/ocorrencias/{id}`  | logado  | Detalhe; o app consulta a cada 3 s até o resumo sair do `PENDENTE` |
| `PUT /api/ocorrencias/{id}`  | logado  | Atualiza a triagem (ex.: sintomas novos ou outra unidade): mesmas regras do registro, urgência recalculada com a hora de agora e resumo novo (`PENDENTE`); com o resumo anterior ainda `PENDENTE`, 409 |
| `DELETE /api/ocorrencias/{id}` | logado | Remove do histórico (204)                   |
| `GET /api/conteudo/primeiros-socorros/{tipoAnimal}` | público | O que fazer, o que não fazer e os cuidados do animal (MVP-02 §6) |

**App no celular apontando para esta API:** no `.env` do app,
`EXPO_PUBLIC_API_URL=http://<ip do computador>:8080` (o mesmo Wi-Fi), e `npx expo start --clear`.

### Testes

```bash
./gradlew test
```

### Docker

```bash
docker build -t antidoto-api .
docker run -p 8080:8080 antidoto-api
```

---

## ☁️ Deploy no Render

O [`render.yaml`](render.yaml) é um **Blueprint**: cria de uma vez o Postgres grátis e a API (Docker),
na mesma região, ligados pela rede interna.

1. Suba este repositório no GitHub (pode ser privado).
2. No [Render](https://dashboard.render.com): **New → Blueprint** → conecte o GitHub e escolha o
   repositório → **Apply**.
3. Espere o primeiro deploy: o build com o Gradle leva alguns minutos, e a subida, 1 a 2 minutos no
   plano grátis. Fica pronto quando `https://<sua-api>.onrender.com/actuator/health` responder
   `{"status":"UP"}`.
4. Copie a URL do serviço (algo como `https://antidoto-api.onrender.com`; se o nome já existir, o
   Render acrescenta um sufixo).

Na criação, o Render pede o valor da `GROQ_API_KEY` (a chave da IA, que não vai para o Git). Se
deixar em branco, a API funciona com o resumo do modelo padrão.

O Render gera o `JWT_SECRET` sozinho e entrega o banco em `DATABASE_URL`
(`postgresql://usuario:senha@host/banco`); a API converte para JDBC na subida (`BancoDoRender`).
Cada push na branch do serviço faz um deploy novo.

**Blueprint criado antes do Passo 7?** O Render só pede a chave na criação: num Blueprint que já
existe, ele ignora a `GROQ_API_KEY`. Abra o serviço → **Environment** → **Add Environment Variable**,
crie `GROQ_API_KEY` com a chave e salve (o Render faz um deploy novo). As outras duas
(`IA_HABILITADA` e `IA_MODELO`) chegam pelo próprio `render.yaml`.

### Como está no ar hoje (criado à mão)

A API no ar foi criada sem o Blueprint, com o mesmo resultado:

1. **New → Postgres** (Free, Virginia) e copiar a **Internal Database URL**.
2. **New → Web Service** → o repositório → Language **Docker**, a **mesma região** do banco, Free.
   Variáveis: `DATABASE_URL` (a URL interna inteira), `JWT_SECRET` (base64 de 32 bytes),
   `CORS_ORIGENS`, `CARREGAR_DADOS_INICIAIS`, `GROQ_API_KEY`, `IA_HABILITADA=true` e `IA_MODELO`.
   Em Advanced, **Health Check Path** `/actuator/health`.

**Repositório na organização:** o Render só enxerga um repositório de organização se o app dele no
GitHub estiver instalado nela (um dono da organização aprova). Enquanto isso, o serviço usa um
**fork pessoal**: cada mudança entra por PR na organização e depois o fork é sincronizado
(**Sync fork**, ou um PR da organização para o fork); o Render faz o deploy sozinho a cada commit
no fork.

### Para a API não dormir

No plano grátis, a API dorme depois de 15 minutos sem receber requisições e leva cerca de 1 minuto
para acordar, mas o app desiste em 10 segundos ("Sem conexão"). Crie um monitor grátis no
[UptimeRobot](https://uptimerobot.com) (tipo HTTP(s), URL `https://<sua-api>.onrender.com/actuator/health`,
a cada 5 minutos): ele mantém a API acordada.

As 750 horas grátis por mês são da conta inteira do Render. Um serviço acordado o mês todo usa cerca
de 744 h: se outro serviço grátis da mesma conta também ficar acordado, as horas acabam e o Render
suspende os serviços grátis até o mês seguinte.

### Limites do plano grátis

- **Postgres:** 1 por conta, 1 GB, e **expira 30 dias depois de criado** (mais 14 dias para fazer
  upgrade antes de ser apagado). Se a conta já tiver um Postgres grátis, o Blueprint falha.
- **API:** 512 MB e 0,1 de CPU. O Dockerfile já ajusta a JVM (heap em 60%, coletor simples, JIT leve):
  aqui ela ficou em ~340 MB, já com o Spring AI.

### App apontando para a nuvem

No `.env` do app: `EXPO_PUBLIC_API_URL=https://<sua-api>.onrender.com` e `npx expo start --clear`.
Login de teste: `cidadao@sospeconha.dev` / `Teste@123`.

---

## ⚙️ Variáveis de ambiente

Modelo em [`.env.example`](.env.example). Nenhuma é obrigatória no computador.

| Variável                           | Para quê                                                  |
| ---------------------------------- | --------------------------------------------------------- |
| `PORT`                             | Porta (o Render define sozinho). Padrão: 8080             |
| `DATABASE_URL`                     | Postgres no formato `postgresql://usuario:senha@host/banco` (o Render preenche pelo `render.yaml`). Sem ele e sem `DB_URL`, H2 em memória |
| `DB_URL`, `DB_USUARIO`, `DB_SENHA` | Postgres configurado à mão (JDBC). Tem prioridade sobre o `DATABASE_URL` |
| `JWT_SECRET`                       | Segredo do JWT em base64, 32+ bytes (`openssl rand -base64 32`). Sem ele, um aleatório por subida: o login cai quando a API reinicia |
| `CORS_ORIGENS`                     | Origens do navegador, separadas por vírgula. Padrão: `http://localhost:8081` (Expo Web). O app no celular não usa CORS |
| `CARREGAR_DADOS_INICIAIS`          | `false` desliga os usuários de teste. Padrão: `true`       |
| `IA_HABILITADA`                    | `true` liga o resumo com IA. Padrão: `false` (só o modelo padrão) |
| `GROQ_API_KEY`                     | Chave do Groq (`gsk_...`). Só na API, nunca no Git nem no app |
| `IA_MODELO`                        | Modelo do Groq. Padrão: `openai/gpt-oss-120b`             |
| `IA_URL`                           | Endereço da API compatível com a OpenAI. Padrão: `https://api.groq.com/openai/v1` |

---

## 📂 Estrutura

```
src/main/java/br/com/fiap/antidoto/
├── config/       RelogioConfig (Clock das regras de tempo), OpenApiConfig (Swagger), AssincronoConfig (@Async), DadosIniciais (usuários, unidades e exemplos de teste), BancoDoRender (DATABASE_URL → JDBC)
├── controller/   recebe, valida (@Valid) e chama o service
├── dto/          records de request e response, com os nomes do contrato; PaginaResponse; erro/
├── exception/    TratadorGlobalDeErros (formato de erro do contrato) e as exceções da API
├── mapper/       entidade → DTO
├── model/        entidades JPA e enums
├── repository/   Spring Data JPA
├── security/     SecurityConfig (rotas públicas e protegidas, CORS), JwtService, JwtFiltro, RespostasDeSeguranca (401/403)
├── ia/           ResumoIaService (o resumo pelo Groq, com tempo limite e conferência da resposta) e ResumoModeloPadrao (o modelo de texto, a reserva)
├── mensageria/   OcorrenciaCriadaEvento, OcorrenciaAtualizadaEvento e ResumoOcorrenciaConsumer (gera o resumo depois do commit, em segundo plano)
└── service/      regras de negócio (AuthService, UsuarioService, UnidadeService, CompatibilidadeSoroService, DistanciaService, AvistamentoService, UrgenciaService, OcorrenciaService, ConteudoService)
src/main/resources/
├── application.properties
└── db/migration/ V1__criar_tabelas.sql (Flyway, SQL do PostgreSQL)
docs/             pacote do MVP (MVP-00, MVP-01) e o contrato
render.yaml       Blueprint do Render (Postgres grátis + API)
Dockerfile        imagem da API (build com o Gradle, roda só com a JRE)
```

As convenções estão no [`CLAUDE.md`](CLAUDE.md).

---

## Diferenças em relação ao MVP-01 (decididas em 10/11)

Como esta API não é a entrega de Java, ela segue o contrato à risca, mas simplifica o que o app
não enxerga:

| No MVP-01                              | Aqui                                                                           |
| -------------------------------------- | ------------------------------------------------------------------------------ |
| Maven, Spring Boot 3.5                 | Gradle e Spring Boot 4, como no Java do Challenge (java-afetto)                |
| Oracle em produção                     | PostgreSQL no Render (o Render não tem Oracle); o SQL do Flyway é de Postgres  |
| RabbitMQ para o resumo                 | Resumo em segundo plano dentro da API (`@Async`), o modo sem fila do MVP-01    |
| `br.com.sospeconha.api`, "SOS Peçonha" | `br.com.fiap.antidoto`, "Antídoto" (o nome do app)                             |
| CRUD de unidades do GESTOR e `GET /api/ocorrencias/unidade/{id}` | Fora: o app não usa. As leituras públicas (`GET /api/unidades` e `/{id}`) existem |
| Prompt do MVP-01 §8, `max-tokens 300`  | O mesmo prompt com regras a mais (copiar urgência, motivos e destino; alerta só com `(ALERTA)`; sem numeração), porque nos testes a IA chamou dor e inchaço de alerta e inventou motivo. Até 800 tokens, porque o `gpt-oss-120b` gasta tokens raciocinando. A conferência também descarta outro nível de urgência, alerta inventado e campo vazio |
| Primeiros socorros só no app (MVP-02 §6) | `GET /api/conteudo/primeiros-socorros/{tipoAnimal}` (público), com o mesmo texto: o app mostra só dado que vem da API |
| Ocorrência sem edição                  | `PUT /api/ocorrencias/{id}`: a pessoa atualiza a triagem (sintomas, unidade) e o resumo é refeito; a ocorrência ganha o CRUD completo |
| Groq com `llama-3.3-70b-versatile`     | Groq com `openai/gpt-oss-120b`: o Groq desligou o Llama 3.3 70B nos planos grátis e developer em 16/08/2026 e indica este no lugar ([deprecations](https://console.groq.com/docs/deprecations)) |

---

_Antídoto — Global Solution 2026/2 · FIAP_
