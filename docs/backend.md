# Backend (Java 25)

> ⚠️ **REGRA**: mudou uma rota, um comportamento de `Service`, o schema ou uma variável de ambiente? Atualize este arquivo e os diagramas [03-backend-camadas](diagramas/03-backend-camadas.puml), [04](diagramas/04-sequencia-lancamento.puml), [05](diagramas/05-sequencia-login.puml) e [07](diagramas/07-er-modelo-dados.puml) **no mesmo PR**.

## Como rodar

```sh
mvn -o package          # JAR gordo em target/ponto.jar
./ponto                 # menu do terminal
./ponto --serve         # API + web em $PONTO_PORT (padrão 8090)
./ponto --check-db      # testa conexão e migrações e sai
```

Requisitos: Java 25, PostgreSQL rodando. Maven só serve para compilar.

## Camadas (e o que pode/deve chamar o quê)

![Camadas do backend](diagramas/img/backend-camadas.svg)

| Pacote | Papel | Regras |
|---|---|---|
| `ponto` (raiz) | `Main` (entrada), `HealthCheck` (usado pelo compose), exceções (`Bad`, `NotFound`, `Conflict`, `NeedConfirm`, `StorageFailure`, `Problema`, `Eof`) e `Ver` (versão) | — |
| `controller` | Menu CLI (`App`), API HTTP (`Web`), `Auth`, rotas por recurso (`NoteRoutes`, `KanbanRoutes`, `DiarioRoutes`, `AssistRoutes`) | **Nunca** fala com `infra` direto — só com `Service`. Nunca contém regra de negócio. |
| `app` | Fachada `Service` + apps por capacidade (`TimesheetApp`, `CompanyApp`, `NotesApp`, `KanbanApp`, `FilesApp`, `DiarioApp`) | Orquestra; valida; lança as exceções de negócio. |
| `model` | Records (`Entry`, `Company`, `Note`, `Kanban*`, `Report`, `Bank`, `Tag`, `Folder`…) e regras puras (`BankRules`, `Holidays`, `Parse`, `Str`, `Dur`, `Time`) | Sem SQL, sem HTTP. Testável isoladamente. |
| `infra` | `Db` (conexão + migrações), stores com SQL (`Repo`, `NotesStore`, `KanbanStore`, `CompaniesStore`, `Diario`), `Files` + `Minio` (anexos), `Assist` (Ollama) | Único lugar com SQL. |
| `view` | `Json`/`Json3` (HTTP), `Csv`/`Xls`/`Pdf` (exportação), `In`/`Out` (terminal) | Formatação, não regra. |

## Ciclo de vida de uma requisição HTTP

![Sequência de lançamento](diagramas/img/sequencia-lancamento.svg)

1. **`app.before`** (`controller/Web.java`) roda para toda requisição:
   - gera `X-Request-ID`, aplica headers de segurança (CSP, HSTS, nosniff, frame-deny…);
   - em `/api/*` (exceto `/api/health` e `POST /api/login`) exige cookie de sessão (`auth.require()` → 401);
   - exige `companyId` na query (catalogo de empresas e taxonomy são exceção) e cria `Service.forCompany(id)` guardado como atributo da requisição.
2. O handler do endpoint pega `service(ctx)` e delega.
3. Erros de negócio viram JSON: `NeedConfirm` → **409 com `need`** (o frontend pergunta e reenvia com a flag de confirmação), `Conflict`/`NotFound` → 409/404, `Bad` → 400, `Problema` → 400 genérico (mensagem não vaza detalhe), exceção inesperada → 500 com `requestId`.
4. `app.after` força `Cache-Control: no-store` em tudo menos `/assets` (que é imutável, com hash no nome).

### Endpoints principais

| Método e rota | O que faz |
|---|---|
| `GET /api/health` | Saúde: banco, storage, versão, auth — usado pelo Docker |
| `POST/GET/PUT/DELETE /api/login · /api/me · /api/logout` | Sessão |
| `GET /api/home` | Resumo do painel (dia atual, saldo, abertura) |
| `GET/POST /api/entries`, `GET/PUT/DELETE /api/entries/{id}` | Registros de ponto |
| `DELETE /api/days/{dia}`, `PUT /api/days/{dia}/falta · /normal` | Dia inteiro (apagar, falta, "dia normal") |
| `GET /api/day · /api/holiday · /api/bank · /api/sum` | Dia, feriado, banco de horas, resumo |
| `GET /api/export/{kind}` | `pdf` / `xls` / `csv` (anexo) |
| `GET/PUT /api/config` | Nome do usuário, dias de folga, jornada, vacuum |
| `GET/POST/PUT/DELETE /api/companies(…)` | CRUD de empresas + ativação |
| `web/src/api.ts` ↔ `NoteRoutes` / `KanbanRoutes` / `DiarioRoutes` / `AssistRoutes` | Notas, tags, pastas, anexos, kanban, diário, sugestões IA |

**Ao criar ou mudar um endpoint: atualize também `web/src/api.ts` e `web/src/types.ts`** (os tipos TypeScript são espelhados à mão).

## Autenticação (Auth.java)

![Sequência de login](diagramas/img/sequencia-login.svg)

- Credenciais vêm de `PONTO_WEB_USER` + `PONTO_WEB_PASSWORD` (definidos **juntos**; se nenhum existir, auth é `null` e a web fica aberta — uso local).
- Login: rate limit de 5 tentativas/IP em 60 s, comparação em tempo constante (`slowEquals`), token aleatório de 32 bytes em **memória** (TTL 7 dias, renovado a cada uso).
- Cookie `ponto_session`: `HttpOnly; SameSite=Lax; Secure` quando HTTPS (detectado por `X-Forwarded-Proto`).
- Reiniciar o processo encerra todas as sessões (não há tabela de sessão).
- Logout: remove o token e apaga o cookie.

## Banco de dados

![Modelo de dados](diagramas/img/er-modelo-dados.svg)

- **Uma conexão JDBC** aberta por `Db.open`; todas as empresas compartilham conexão e `lock`.
- Migrações versionadas in `infra/Migrations.java` (`VERSION = 7`): a new installation creates the full schema in one transaction; **each step advances exactly one version** (`Migrations.java` stamps only v7, `test/ponto/TestDb.java` and `web/test/fixture` assert the history is 1…7 and that a database from the future is refused). v6 → v7 adds `vault_meta` + `vault_item`.
- Tables: `company`, `ponto`, `dia_flag`, `cfg`, `folder`, `note`, `tag`, `note_tag`, `note_file`, `kanban_column`, `kanban_card`, `kanban_block`, `kanban_card_file`, `object_job`, `day_digest`, `vault_meta`, `vault_item`, `schema_migrations`.
- The vault is **not** partitioned by company: one master password opens every company's records, and `vault_item.company_id` is a tag the server may filter and orphan-check — never a secret. A trigger stops a credential from moving between companies, and deleting a company that still holds credentials is refused (409) until its rows are wiped.
- No column of `vault_meta`/`vault_item` ever holds plaintext: `salt`, `iterations` and `kdf` are public KDF parameters, `check_blob` and `blob` are AES-GCM envelopes (`iv ‖ ciphertext ‖ tag`) produced by the client. Nothing in the backend can open them — there is no key and no password here.
- Partição por empresa via `company_id` + FKs compostas (ex.: `UNIQUE(id, company_id)`), com trigger garantindo que nota e tag sejam da mesma empresa.
- **Mudou o schema?** Incremente `VERSION`, escreva a migração in-place, e lembre que o deploy usa snapshot e migração de cópia (ver `deploy/README.md`).

## Regras de negócio onde elas moram

| Regra | Onde |
|---|---|
| Datas `dd/mm/yyyy`, horas `NN:NN`, decimais `0.75` | `model/Parse`, `model/Str`, `model/Dur` |
| Feriados Brasil + SP (estado e cidade) | `model/Holidays.java` (código, sem internet) |
| Saldo do mês, plano diário, tolerância | `model/BankRules` + `app/TimesheetApp` |
| Avisos de par sobreposto, virada de meia-noite | `app/TimesheetApp.inspect()` → `NeedConfirm` |
| Relatórios e totais | `model/Report` + `view/Csv|Xls|Pdf` |
| Dias de folga por empresa, jornada diária | `company.off`, `company.daily` (parse em `Service.offDates`/`parseDailyMins`) |

## Anexos (Files + Minio)

- Metadados em `note_file`/`kanban_card_file` (`state`: `uploading → ready | failed`); bytes vão ao MinIO/S3.
- Todo PUT/DELETE passa pela fila `object_job`; o worker de `FilesApp` processa com retries (`attempts`, `last_error`).
- Sem storage configurado, o resto do app funciona; `/api/health` reporta `storage: disabled|ok|unavailable` e `POST /api/storage/retry` força retry.
- Limite de upload: `PONTO_UPLOAD_MIB` (padrão 20 MiB), refletido no `maxRequestSize` do Javalin.

## Diário + IA (opcional)

- `DiarioRoutes` + `app/DiarioApp` geram o resumo do dia (`day_digest`) a partir do ponto, notas e kanban.
- `infra/Assist` fala com Ollama em `127.0.0.1` para sugerir **tags de nota** e **categorias de justificativa**. Sem Ollama: botões não aparecem, nada quebra.

## Cofre de senha (zero-knowledge)

![Camadas do backend](diagramas/img/backend-camadas.svg)

The vault is the one part of the app where **the server is deliberately dumb**. The browser derives a key
from the master password (PBKDF2-HMAC-SHA256 → AES-256-GCM) and only ever sends sealed blobs. The backend
stores, counts, filters and deletes those blobs without ever being able to read them.

| Piece | File | Job |
|---|---|---|
| Parameters | `model/VaultCrypto.java` | KDF, salt/iv/tag sizes, iteration bounds, the AAD strings, `looksSealed()`, `encode()`/`decode()` |
| Envelope | `iv (12) ‖ ciphertext ‖ authTag (16)`, base64 | The same bytes WebCrypto writes — `test/vault-fixtures.json` and `test/vault-node.mjs` are the contract |
| Records | `model/VaultPayload.java` | The plaintext JSON (`app`, `user`, `pass`, `desc`, `company`) the **client** seals. The backend only checks its shape, never its contents |
| Policy | `app/VaultApp.java` | A vault must be sealed before records; rekey moves all of it or none of it |
| Storage | `infra/VaultStore.java` | Opaque blobs, revisions, counters, orphan guards. No cipher, no plaintext model in the imports |
| HTTP | `controller/VaultRoutes.java` | Meta, seal, items (single/bulk), rekey, wipe. 423 + `need: "create"` when the cofre does not exist yet |
| Backup file | `view/VaultFile.java` | `ponto-cofre/1`: public header (`format`, `kdf`, `salt`, `iterations`, `count`) + one sealed blob holding the whole list |
| Terminal | `controller/App.java` (`v`) | Unlock, list masked, show one, new/edit/delete, generator (`SecureRandom`), backup, import, rekey, wipe |

**What the server can and cannot do**

| Question | Answer |
|---|---|
| "Which company owns this credential?" | It can — `company_id` is a plaintext tag, used for counting, filtering and orphan checks |
| "Is this a real sealed envelope?" | It can — base64 shape, minimum length, the `looksSealed()` rule |
| "Does this blob decrypt? Is the password right?" | It cannot. There is no key and no password in the process, and there never will be |
| "Did the user seal with a *different* key?" | It cannot tell. Their own client will refuse it (GCM tag mismatch), which is why the unlock test exists |
| "Can I reset the master password?" | No. There is no server-side recovery — losing the password loses the cofre. That is the design, not a gap |

**Invariants**

1. **PBKDF2 is written by hand.** `PBEKeySpec` feeds the JCE a `char[]` and the provider hashes the low 8
   bits of each UTF-16 unit — so `café` would produce a *different* key in Java than in the browser, and every
   real password with accents would break. `VaultCrypto.deriveKey()` hashes UTF-8 bytes, exactly as WebCrypto does.
2. **AAD is *authenticated*, not encrypted.** `updateAAD()`, never `update()` on the context; the AAD binds a
   blob to `ponto:vault:item:<companyId>`, the verifier to `ponto:vault:check`, a backup to its record count.
   Java appends the auth tag to `doFinal()` output, so the envelope is `iv ‖ sealed` — the same bytes the browser
   and the Node mirror produce.
3. **`423 + need: "create"` before a seal exists.** Writing records against a vault with no verifier would leave
   blobs nobody can ever unlock. `VaultSealed` carries `need` and `Web.java` turns it into 423.
4. **Rekey is atomic.** The client decrypts everything, re-seals everything, and submits the full set; one
   transaction replaces every blob plus the verifier. A half-done rekey would open for nobody.
5. **The terminal holds the key only in memory** (`App.cofreKey`), drops it on `lock`, and prints secrets only on
   explicit request (`v s <id>`). Lists show `•••••`.
6. **A backup file is self-verifying.** The outer seal covers the whole list and `count` is the AAD, so a deleted,
   swapped or forged record breaks the tag. A plaintext list is refused *before* any key is derived.

**Terminal keys** (`v`, in the menu): `v` state · `v c` create/open · `v l [text]` list (masked) · `v n` new ·
`v e [id]` edit · `v s [id]` show one secret · `v x [id…]` delete · `v g [n]` generate · `v b [file]` backup ·
`v i [file]` import · `v w` forget this company · `v k` new master password · `v q` lock.
Only `v c` and `v n` may seal an unsealed vault, and both ask for the password twice — so a master password is
never chosen by accident. Everything else refuses an unsealed vault with `v c creates it` instead.

## Variáveis de ambiente

| Variável | Padrão | Efeito |
|---|---|---|
| `PONTO_DB` | `jdbc:postgresql://127.0.0.1:5432/ponto` | JDBC URL (ou `--db=`) |
| `PONTO_PORT` | `8090` | Porta do `--serve` (ou `--port=`) |
| `PONTO_WEB_USER` / `PONTO_WEB_PASSWORD` | — | Login da web (definidos juntos) |
| `PONTO_DATA_DIR` | `~/.local/share/ponto` | Staging de uploads |
| `PONTO_CSV_SEP` | `;` | Separador do CSV (`,` se preferir) |
| `PONTO_UPLOAD_MIB` | `20` | Limite de upload |
| `PONTO_S3_ENDPOINT/ACCESS/SECRET/BUCKET` | — | MinIO/S3 dos anexos |
| `PONTO_TZ` | `America/Sao_Paulo` | Fuso (compose) |
| `PONTO_USER` | — | Nome padrão do usuário (config salva depois em `cfg.who`) |

## Testes

- `test/` — JUnit (configurado no POM, apesar de fora de `src/test/java`), smoke do terminal (`test/smoke.sh`, `feed.txt`, `feed-cofre.txt`), e `test/Verify.java`/`Normal.java` como fixtures.
- `test/feed-cofre.txt` drives the whole cofre through the real `ponto.Main` (create, two credentials, list, generate,
  backup, re-key, import, delete, lock). `test/smoke.sh` replays it and then greps: the session must show `•••••` and
  never the typed password, and the backup must be `ponto-cofre/1` holding no plaintext.
- The vault is checked against a **third implementation on purpose**: `test/vault-node.mjs` is WebCrypto in Node, the
  stand-in "browser" that both `test/ponto/view/VaultFileTest.java` and the frontend suite must agree with, byte for byte.
  `test/CofreCli.java` runs the real app end to end and asserts the database holds no plaintext for any secret it planted.
- Backend roda no CI (`.github/workflows/`) com PostgreSQL provisionado.
- **Mudou uma regra?** Adicione/ajuste o teste junto — e atualize a doc.

## Glossário rápido

- **registro (entry)** — um par entrada/saída de um dia (`ponto`).
- **falta / dia normal** — flags do dia inteiro (`dia_flag`).
- **saldo** — diferença entre o trabalhado e o plano (`bank`).
- **empresa (company)** — partição dos dados; web exige `companyId`.
- **diário (day_digest)** — resumo do dia (ponto + notas + kanban, com IA opcional).
