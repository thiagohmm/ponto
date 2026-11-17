# ponto

Timesheet in the terminal: PostgreSQL, Java 25, one command, one database,
three report formats. Built for the Brazilian "punch card" habit — day, in, out,
justification — with dates as `dd/mm/yyyy` and decimal hours as `0.75`.

```
--------------------------------------------------------
  P O N T O
  Ana                   12 records
  this month 46:30   open 1   since 20/05/2026
  saldo do mês +5:02   ·   plano 40 min a menos/dia
  jdbc:postgresql://127.0.0.1:5432/ponto
--------------------------------------------------------
  1 new     2 edit    3 delete  4 list
  5 sum     6 find    7 pdf     8 xls
  9 csv     d date    c config  f falta
  ? help    x clear   0 quit
--------------------------------------------------------
>
```

## Entendendo o projeto (para quem está chegando)

O **Ponto** é um cartão de ponto eletrônico brasileiro: você registra **dia, entrada, saída e
justificativa** (`dd/mm/yyyy`, horas `NN:NN`, decimais `0.75`) e ele calcula totais, saldo do mês
e banco de horas, avisa sobre pares sobrepostos e já conhece os feriados (Brasil + SP) sem internet.

Dois jeitos de usar **o mesmo programa e o mesmo banco**:

* **Terminal** — `ponto` abre um menu interativo (novo, editar, listar, somar, PDF/XLS/CSV).
* **Navegador** — `ponto --serve` sobe uma API HTTP (Javalin) e uma SPA React (PWA) na porta 8090.

Como está organizado (uma olhada de 30 segundos):

| Camada | Onde | Papel |
|---|---|---|
| Apresentação | `controller/` (menu CLI, rotas HTTP, login) | Formatar perguntas/respostas — **sem regra de negócio** |
| Negócio | `app/Service` + apps (`TimesheetApp`, `NotesApp`, `KanbanApp`…) | Fachada única usada pelo CLI **e** pela web |
| Dados | `infra/` (JDBC, migrações, stores, MinIO) | Único lugar com SQL |
| Regras puras | `model/` (`Entry`, `BankRules`, `Holidays`…) | Testável sem banco |
| SPA | `web/` (React 19 + Vite + TS) | 1 aba = 1 arquivo, todo HTTP em `api.ts` |

## Documentação

A referência completa (para desenvolvedores, com diagramas PlantUML) está em [`docs/`](docs/README.md):

* [Visão geral da arquitetura](docs/arquitetura.md) — comece por aqui
* [Backend (Java)](docs/backend.md) · [Frontend (React)](docs/frontend.md)
* [Diagramas](docs/README.md#diagramas-plantuml) — contexto, contêineres, camadas, sequências, ER, deploy

> ⚠️ **REGRA DE MANUTENÇÃO DA DOCUMENTAÇÃO**
> Ao **mudar qualquer funcionalidade**, atualize também a documentação e os diagramas
> correspondentes **no mesmo PR/commit** — rota nova → `docs/backend.md` + `web/src/api.ts`/`types.ts`;
> página nova → `docs/frontend.md`; schema novo → `docs/backend.md` + diagrama ER.
> **Documentação desatualizada é bug.**

## Requirements

* **Java 25** — the sources are compiled with `--release 25`.
* Maven, only to build; the produced jar runs on its own.
* The PostgreSQL JDBC driver, PDFBox, POI and Jackson are already inside the jar,
  as is the React build. The Brazilian calendar is code (`model/Holidays.java`),
  not a library. Nothing in the app reaches the network.

## Build and run

```sh
mvn -o package      # fat jar  ->  target/ponto.jar
./ponto             # run straight from the project
./ponto --serve   # same database, in the browser (React)
./install.sh      # jar -> ~/.local/share/ponto, launcher -> ~/.local/bin/ponto
ponto             # anywhere afterwards
```

See [operation, backup and recovery](deploy/README.md) for the verified deploy and rollback procedure.

The terminal menu is unchanged. `--serve` starts Javalin on `$PONTO_PORT` (default 8090) and serves the React build from the jar. Both talk to the same PostgreSQL database (`$PONTO_DB` / `--db=`, a JDBC URL). Web requests select their company explicitly with `companyId`; each tab remembers its own choice without changing the terminal preference.

The web app is a **PWA**: open it over HTTPS (or localhost) to install it and use the offline shell. Plain LAN HTTP serves the web interface; service workers require a secure context. See `web/public/sw.js`, registered in production only. Every response carries the security headers (CSP, HSTS, `nosniff`, frame-deny) set in `controller/Web.java`, and the Jetty and Caddy layers keep their `Server` identity hidden; the plain LAN address is meant for the home network only.

In the form the times are masked as `NN:NN` (digits only, `830` types
`08:30`, an `agora` button fills the clock), and **Salvar e lançar outra**
keeps the day on screen: register several in/out pairs of the same day
without reopening the page — the balance asks for confirmation over
overlapping pairs.

```sh
npm --prefix web ci && npm --prefix web run build   # writes web/dist; Maven packages it
mvn package
./ponto --serve --port=8090
```

On the LAN host `192.168.0.8` the database is the `ponto` role and database inside the existing `marketing-postgres` container (`PONTO_DB_URL` in the server `.env`). The deploy script copies **only** the jar and compose files — never a database dump from this machine.

```sh
./deploy/deploy.sh    # https://192.168.0.8:8443  (http://192.168.0.8:8090 → redirect)
```

### Assistência local (Ollama, opcional)

No `--serve`, o Ponto pode sugerir **tags de notas** e **categorias/frases de
justificativa** com um modelo minúsculo local. O browser só fala com `/api/assist/*`;
o Java chama o Ollama em `127.0.0.1`. Sem Ollama o app segue igual — os botões
não aparecem.

```sh
# instalar Ollama, depois:
ollama pull qwen2.5:0.5b
./ponto --serve
```

Variáveis (todas opcionais):

| env | default | efeito |
| --- | --- | --- |
| `PONTO_ASSIST` | ligado | `0` / `false` / `off` desliga de vez |
| `PONTO_OLLAMA_URL` | `http://127.0.0.1:11434` | base do Ollama |
| `PONTO_ASSIST_MODEL` | `qwen2.5:0.5b` | nome do modelo no `ollama list` |

Sugestões nunca gravam sozinhas no banco: tags só após aceite; justificativa
só preenche o campo se você clicar na frase. O cálculo do banco de horas e o
parse de data/hora não passam pelo modelo.

### Files attached to notes

Each note can hold files (drag them onto the note, or the *anexar* button).
The row lives in PostgreSQL (`note_file`), the bytes in an S3-compatible object
store — no SDK, a minimal SigV4 client in `ponto/model/Minio.java`:

* **MinIO** runs next to the app in `docker-compose.yml`: S3 API on the internal container network, browse/download web console bound to `127.0.0.1:9001`, data in
  `/home/thiagohmm/.local/share/ponto-minio`.
  The official `minio/minio` images stopped being published in October 2025,
  so the compose pulls the current release that Chainguard builds from source
  (`cgr.dev/chainguard/minio`, pinned by digest). Any S3-compatible store works: point
  `PONTO_S3_ENDPOINT` at it.
* Config via environment: `PONTO_S3_ENDPOINT` (required, e.g.
  `http://minio:9000` inside compose), `PONTO_S3_ACCESS`, `PONTO_S3_SECRET`,
  `PONTO_S3_BUCKET`, `PONTO_S3_REGION`. Access and secret are required when an endpoint is configured; deployment generates credentials on the server.
* Without an endpoint the app runs fine — the attachment button just says
  storage is not set, and the config page shows the status.

The launcher finds a Java 25+ runtime by itself (`$PONTO_JAVA`, then
`/usr/libexec/java_home -v 25`, then the asdf install, then `java` on the PATH)
and adds `--enable-native-access=ALL-UNNAMED` so the JVM stays quiet on Java 25.

### Password vault ("cofre")

The app also keeps passwords, and it keeps them **without ever being able to read them**:
the browser derives a key from a master password (PBKDF2-HMAC-SHA256, 310 000 iterations) and encrypts each
credential with AES-256-GCM before it leaves the page. PostgreSQL stores sealed blobs, a salt, an iteration count
and a verifier — no plaintext, no hints, no "last used", nothing to hand over.

```
--------------------------------------------------------
  C O F R E
  4 records   ·   this company 2   ·   idle lock 5 min
  search: ____   company: [ all        ]
  3  ACME        ana.santae          ••••••••        copy
  2  Router     admin               ••••••••        [show] copy
--------------------------------------------------------
```

* **One master password, every company.** Unlocking once shows all companies' records; the company column is a tag
  you can filter by, not a separate vault. Deleting a company is refused until its credentials are wiped
  (`409`), and *forget this company* removes only that tag's rows.
* **No recovery, on purpose.** The server cannot reset a master password because it never had one. Lose the
  password and the cofre is empty forever — keep a sealed backup (`ponto-cofre/1`) somewhere you trust.
* **Sealed backups.** The file carries a public header (format, salt, iterations, record count) and one blob
  sealing the whole list, so a file with a record deleted, swapped or forged fails the seal instead of importing
  half the vault. A file that stores passwords in the clear is refused before any key is derived.
* **In the browser:** generator (CSPRNG, rejection sampling — no `Math.random`), honest entropy meter, reused
  password detector, click-to-copy with the clipboard scrubbed after 15 s, rows hidden again after 30 s, auto-lock
  after idle time (a hidden tab does *not* pause the clock), and a key that is non-extractable and never persisted.
* **In the terminal:** `v` shows the state, `v c` opens (or creates) the cofre — list masked, `v s <id>` shows one
  secret, `v n` adds, `v g` generates, `v b` / `v i` back up and import, `v k` changes the master password, `v q`
  locks. Only `v c` and `v n` can seal a vault, and both ask for the password twice: nothing creates a master
  password by accident. The key lives in memory and is dropped on lock; nothing is written to disk except the backup
  you ask for.

The Java and the browser implementations are pinned to the same bytes by `test/vault-fixtures.json`, and
`test/vault-node.mjs` (WebCrypto in Node) is the third implementation both test suites must agree with.

## Data


* Database: `$PONTO_DB`, a JDBC URL (default `jdbc:postgresql://127.0.0.1:5432/ponto`, or `--db=`).
* Upload staging: `$PONTO_DATA_DIR`, default `~/.local/share/ponto`.
* One table, `ponto`: `id, dia, tin, tout, just, crea`.
* One flag table, `dia_flag`: `dia, flag` — whole-day flags: the banco de
  horas **falta** (`f` / the web button) and **normal** (a day off ruled as a
  normal working day — the web toggle). A flag of the day, never of a punch
  line.
* Any row can be edited or deleted at any time — nothing is locked to "today".
* A line may hold one clock (an open punch) or none at all (just a note).

## Keys

| key | what it does |
|-----|--------------|
| `1` | new entry: day, in, out, justification — the day's existing records and their total appear as soon as you give the day, and a weekend or day off is pointed out |
| `2` | edit an entry (`2` to pick from a list, `2 7` to go straight to id 7) — moving a record onto a weekend or day off warns too |
| `3` | delete — asks **all day or id** (`3`, `3 a`, `3 01/10`, `3 5 6 7`): rows and total shown, one `y`, menu comes back |
| `4` | list the newest records — or by date: `4 2026`, `4 10/2026`, `4 oct`, `4 01/10/2026`; `older` pages, `total` is the running sum |
| `5` | sum: totals per day for a period |
| `6` | find in the justifications (`6 sick`, or `6` then type the text) |
| `7` | export a PDF report (summary page + detail) |
| `8` | export a real Excel 97-2003 `.xls` (BIFF8, not xlsx) |
| `9` | export CSV (UTF-8 BOM, `;` separated, decimal always `.`) |
| `c` | config: user name, your own days off (`off`), database path, vacuum |
| `d` | list by date — asks for a **day, month or year** (`d`, `d 2026`, `d oct`) |
| `f` | falta: `f` toggles today, `f 03/11/2026` that day, `f lista` lists them (banco de horas) |
| `x` | clear the screen |
| `?` | help |
| `0` | quit |

Output stays on the screen. The menu is redrawn *below* whatever the key printed
(the screen is wiped once, on the first paint only), so `4`, `5`, `6` and the
`ok` lines stay readable and in the scrollback — `older` keeps paging until the
last record is shown. Press `x` when you do want a clean screen.

### List by date

`4` and `d` take the same filter. It is matched against the day of each record, so
one word is enough:

| you type | you get |
|----------|---------|
| `4` · `4 50` | the newest records (`50` = how many rows) |
| `4 2026` | every record of 2026 |
| `4 10/2026` · `4 2026-10` | October 2026 |
| `4 oct` · `4 oct 2026` · `4 outubro` | October (this year when no year is given) |
| `4 01/10/2026` · `4 2026-10-08` · `4 5oct` | one day |
| `4 today` · `4 y` · `4 -3` | today, yesterday, three days back |

A bare number 1..999 means *how many rows*, so for October write `10/2026` or `oct`.
Each screen ends with `total` (the running sum, open lines excluded) and `shown N of M`;
`older` brings the rest. Nothing matching says so: `nothing in feb 2026`.

## Days off

`1` and `2` print one yellow line when the day is not a normal working day:

```
  day [10/10/2026] > 03/10/2026
  !   03/10/2026  SAT   weekend
  day [10/10/2026] > 25/12/2026
  !   25/12/2026  FRI   holiday: Christmas
  !   26/12/2026  SAT   holiday: Boxing Day   ·   weekend
  !   16/02/2026  MON   optional: Carnival Monday
```

It is a warning, never a wall: people do work weekends and holidays, and a lost
punch costs more than a punch on the wrong day. `c config` lists the next days off
and asks for `off`, your own days off — `27/10/2026 31/12`, separated by space,
comma or `;`.

The calendar is written in `Holidays.java`: the country list for `BR` plus the
state of São Paulo and the city, so 09/07 and 20/11 count as São Paulo days and
25/01 counts for the city, and Easter (computed there) anchors Carnival, Ash
Wednesday, Good Friday and Corpus Christi. It used to come from jollyday, whose
XML was read at startup — 3 MB of dependency and a parser to load a fixed list.
Four days are added by hand alongside it, checked against the city's own calendar
(Prefecture of São Paulo, Decree 64.862/2025) and listed there with the reason:

| day | what it is |
|---|---|
| 26/12 | Boxing Day, national, missing from the country list |
| Carnival Monday | the list knew the Tuesday only |
| 24/12 · 31/12 | Christmas Eve, New Year's Eve — optional days off |
| Carnival Tuesday · Ash Wednesday | the list says *public*, the decree says *facultative* |

(28/10 "Servant's Day" was removed: it is a public-servant day, not a private
holiday, and the bank must treat a punch there as a normal working day.)

## Banco de horas

The bank is **monthly**. One rule per day, never past today:

| the day | what counts |
|---|---|
| marked as **falta** (`f`) | batido − jornada — the day enters the math even empty, and it wins over every other rule |
| **day off marked as normal** (web toggle) | batido − jornada — the punches pay the jornada, nothing banks; an empty flagged day stays 0, and the day re-enters the dias úteis restantes |
| **day off** (weekend, holiday, optional, your `off`) | batido − 0 — everything worked is banked |
| working day with a closed punch | batido − jornada, with the legal tolerance of ±5 minutes |
| empty working day | ignored (a missed punch is not a falta) |

**Tolerância legal (CLT art. 58 §1º · TST Súmula 366).** Where the rule is
"batido − jornada" (working day and day off marked normal), a day balance
within **±5 minutes counts as 0** — neither computed as hora extra nor
discounted. Past the 5th minute the **whole** day balance counts, the first
minutes included: 8h07 → +7; 8h04 → 0; 7h55 → 0; 7h54 → −6. The daily cap of
10 minutes is the sum of the 5-minute tolerance of the two markings; the app
compares the whole day (batido − jornada), so the effective trigger here is
the 5th minute. A falta keeps batido − jornada untouched (the jornada is owed),
and a day off without the *normal* flag banks everything worked — no jornada,
nothing to tolerate.

`f` toggles the flag for the whole day (it lives in `dia_flag`, never on a punch
line). Marking a falta on a working day that already has closed punches is a
no-op for the balance and says so; marking one on a day off deducts the jornada
and warns in yellow — a warning, never a wall.

The web mirrors it with the **normal** toggle: every day off, weekend included,
shows a `normal` button beside the falta one. Unmarked — the default — the day
banks everything punched; marked, the day is ruled like a normal working day
(batido − jornada), so a holiday worked as an ordinary shift stops feeding the
bank, and the monthly plan counts the day again. A falta on the same day still
wins.

The web **shows the current month by default**; the toolbar month picker covers
one month, and the pair **"do … até"** selects a **span of months** (the list,
its totals and the hours of the span follow the whole range). The
`dia, mês ou ano` input still stands for a single day/month/year search and
overrides the span while it has text. The appearance has a **claro/escuro**
theme — the moon/sun toggle on the toolbar switches on the spot, and Config
keeps *Sistema/Claro/Escuro* (first access follows the system; the choice
stays in the browser's `localStorage`).

The header prints `saldo do mês +5:02 · plano 40 min a menos/dia`: the plan is
`saldo / dias úteis restantes` (from today on, without days off and without
future faltas — a paid day leaves the plan), the last day absorbs the rounding
remainder. A negative bank prints `! banco negativo -3:20 · plano 30 min a
mais/dia` in yellow. `5 sum` repeats the saldo of the period, and the PDF, the
XLS and the CSV carry it too.

## Input

```
dates    08/10  ·  08/10/2026  ·  2026-10-08  ·  5oct  ·  today  ·  y  ·  -3
times    07:30  ·  07h30  ·  0730  ·  8  ·  now
Enter    keeps the value shown in brackets
-        clears the field
```

## Environment

| variable | meaning |
|----------|---------|
| `PONTO_DB` | PostgreSQL JDBC URL |
| `PONTO_USER` | name printed on the reports (handy in scripts) |
| `PONTO_JAVA` | Java runtime used by the launcher |
| `PONTO_CSV_SEP` | set to `,` to get commas instead of semicolons |

## Tests

```sh
./test/smoke.sh
```

Replays `test/feed.txt` into a throwaway database, exports the three formats,
reads them back (`test/Verify.java` opens the PDF with PDFBox and the workbook
with POI) and diffs a digest against `test/expected.txt`. The generation clock,
the test directory and the OS user name are masked, so the golden file holds on
any machine.

Then it replays `test/feed-off.txt` and checks the days off on the real output,
and `test/feed-cofre.txt` through the same `ponto.Main` to check the vault: the
list must come back masked, the typed master password and its secrets must appear
nowhere in the session, and the backup must be `ponto-cofre/1` with no plaintext
inside (re-keyed, imported back, deleted and locked along the way).

```sh
./test/smoke.sh     # PASS  smoke   days off + digest + normal + cofre, session and files in /tmp/ponto-smoke
```

| asserted | why |
|---|---|
| `! 12/10/2026 MON holiday: Our Lady of Aparecida` | 12 October is a national day off |
| `! 17/10/2026 SAT weekend` | the weekend side of the same rule |
| `! 24/12/2026 THU optional: Christmas Eve` | the tier shown when the day is only facultative |
| `ok #1 12/10/2026 08:00-12:00 4:00` | the punch on the holiday **is** saved: a warning, not a wall |
| `day off 12/10/2026 …` | `c config` reads the same calendar |
| *nothing* on `13/10/2026` | a working tuesday stays silent |
| `cofre re-selado` + `importado 2` | changing the master password re-seals every record, and a backup taken before it still opens |
| `•••••` in the list, and *no* `alpha-s3cr3t-one` in the session | the terminal masks secrets; only `v s <id>` prints one, on request |
| `ponto-cofre/1` in the backup, and *no* plaintext of any password inside | the file is sealed, and the CLI never writes a secret in the clear |

The vault has its own tests, run by `mvn test` and `npm test`:

| Test | What it must not get wrong |
|---|---|
| `ponto.model.VaultCryptoTest` | the fixed vectors in `test/vault-fixtures.json` — the same key and the same bytes the browser produces |
| `ponto.view.VaultFileTest` | a backup written by Java opens in the browser's reader and the other way round, via `test/vault-node.mjs` |
| `ponto.infra.VaultStoreTest` | revisions, atomic rekey, wipe, and that upgrading a v6 database keeps the timesheet |
| `ponto.controller.VaultApiTest` | 423 + `need` before a seal, 409 on a stale revision, and no plaintext in any body |
| `ponto.CofreCli` (`test/CofreCli.java`) | the real app: seal, two companies, backup both ways, rekey, wipe — then it greps every text column of every `vault*` table for the secrets it planted |
| `web/test/vault*.test.ts` + `web/test/Cofre.test.tsx` | WebCrypto interop, sealing before the request, copy never revealing a hidden row, the page text holding no secret |
| Playwright (`web/e2e/`) | the whole flow in Chromium: create, save, copy, lock, and the wrong master password refusing |

## Layout

```
src/main/java/ponto/
  Main        composition root: one Db, one Service, one UI per mode
  Problema, Bad, NotFound, Conflict, Eof   the shared failure types
  VaultSealed   "the cofre does not exist yet" -> HTTP 423 with need: "create"
  Ver         NAME and VERSION
  app/        Service (the fachada) + one app per capacity
    Service     the whole vocabulary both UIs may call (one lock)
    VaultApp    the vault's policy: seal before records, rekey all or none
  model/      domain: rules and values, no printing
    Entry, Report, Holidays, Parse, Period, BankRules, JustTaxonomy…
    VaultCrypto      PBKDF2 + AES-GCM, the AADs, the envelope both sides write
    VaultPayload   the plaintext JSON the client seals (never read by the server)
    VaultGen       the terminal password generator (SecureRandom, rejection sampling)
    Minio          the SigV4 client for the attachments' object store
  infra/      the only place with SQL
    Db, Migrations (VERSION = 7), the stores…, VaultStore
  view/       how things are rendered
    Out, In (text, secret), Csv, Pdf, Xls, Cols, Json, Json3
    VaultFile   the sealed backup file, ponto-cofre/1
  controller/ the two UIs; they talk to Service and render views, nothing else
    App         the menu loop, one method per key (v = the cofre)
    Web, Auth, VaultRoutes, NoteRoutes, KanbanRoutes, DiarioRoutes, AssistRoutes
test/         JUnit, feed.txt + feed-cofre.txt, Verify.java, expected.txt, smoke.sh,
              vault-node.mjs (the browser mirror), vault-fixtures.json
```

Dependency rule: `controller -> model`, `view -> model`, and `controller ->`
`view` when rendering; **the model never imports the view** — the JSON builders
and the table columns live in `view/` for that reason. The frontend (`web/`) is
unchanged: it is the browser view of the web mode.


## Quality checks

```sh
npm --prefix web ci
npm --prefix web test
npm --prefix web run build
mvn verify
./test/smoke.sh
python3 -m unittest discover -s test -p test_backup.py
npm --prefix web run test:e2e
```

The Java suite includes transactional upgrades, rollback, request isolation,
revision conflicts, storage failures and report exports. The browser suite uses
a disposable database and covers tab isolation, unsaved edits, conflicts, mobile
navigation and the offline shell. GitHub Actions runs the same checks.
