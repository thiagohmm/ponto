# Plano: Arquitetura MVC no backend Java do `ponto`

## Decisões fechadas (com o usuário)

1. **Escopo**: só o backend Java. O frontend React (`web/`) é a view do modo web e **não muda** — nenhuma chave de API, endpoint ou layout de JSON pode mudar.
2. **Rigor**: MVC **com regra de dependência**, não só pacotes:
   - `controller` (App, Web) só conversa com o `Service`; não acessa `Repo`/`Db` direto.
   - Helpers de apresentação (`Entry.durCol()`, `Entry.hoursCol()`) saem do model para a view.
   - Os builders JSON (`Service.json`, `Service.jsonDay`) saem do model para a view.
3. **Raiz**: `Main`, `Errors` (Bad/Problema/NeedConfirm/Eof) e `Ver` ficam no pacote `ponto`. **Nenhuma** alteração em `pom.xml` (`mainClass ponto.Main`), `Dockerfile`, `install.sh`, `ponto` (launcher) ou `test/smoke.sh`.

## Mapeamento alvo

```
ponto/                 raiz: Main, Errors (Bad, Problema, NeedConfirm, Eof), Ver
ponto.model/           Entry, Report, Db, Repo, Service, Holidays, Parse, Str, Dur,
                       Period  (record extraído do rodapé de In.java:210 — é domínio)
ponto.controller/      App (terminal), Web (Javalin)
ponto.view/            Out, In, Csv, Pdf, Xls, Json3 (mapper do Javalin),
                       Cols (novo: absorve Entry.durCol()/hoursCol()),
                       Json  (novo: absorve Service.json()/jsonDay())
```

Regra de dependência (sentido único): `controller → model`, `view → model`. Model **nunca** importa de controller/view. Controller pode invocar views (App chama Csv/Pdf/Xls.write; callbacks `Consumer<String>` passados ao Service são invocação básica, não import de view).

## Descobertas que guiam a implementação

- As 20 classes são package-private (`final class App` etc.). Ao dividir em pacotes, **classe + construtor + membros usados de outro pacote viram `public`** — e nada além (compilador `-Xlint:all` aponta).
- `Period` hoje vive no rodapé de `In.java` (record `Period(LocalDate, LocalDate)`), usado por `Service`, `Report` e `In.period()`. Extrair para `ponto.model.Period` (public).
- `App` acessa direto: `repo.recent/search/byDay/countDay/count/on/byId/min/max/countOn/between/insert/update/clearTimes/delete/deleteDay/openAll` e `db.cfg/setCfg/file/vacuum/version`. O `Service` já cobre: `on`, `byId`, `list`, `count(q,filter)`, `report()`, `export()`, `home()`, `config()`, `setConfig()`, `create/update/delete/deleteDay`, `who()`, `holiday()`, `inspect()`.
- `Service.json()/jsonDay()` só são usados por `Web.java`. `Entry.durCol()/hoursCol()` são usados por `Out` (via App.table), `Csv`, `Pdf`, `Xls` e `Service.json`.
- `Holidays.extra(offDates(db.cfg("off")))` é registrado em 2 pontos de UI + dentro de vários métodos do Service (lazy). Centralizar em `Service.registerOff()` chamado no startup de cada modo.
- `Main` cria `Db` e cada modo cria seu `Service` (`new Service(db)` em App e Web). Passar a injetar **um único Service** do Main (composition root) — uma instância, um lock.

## Tarefas (cada passo compila e passa no smoke antes do seguinte)

### T0 — Linha de base (antes de mexer em qualquer coisa)
- `mvn -o package` e `./test/smoke.sh` → confirmar `PASS` hoje.
- Capturar baseline da API: iniciar `./ponto --serve --port=8091` sobre um db de teste e salvar `curl` de `/api/home`, `/api/entries`, `/api/day?dia=hoje`, `/api/sum`, `/api/config`, `/api/export/csv` em `/tmp/ponto-baseline/*.json`.
- Salvar também uma sessão terminal: `PONTO_DB=/tmp/ponto-baseline/x.db java -cp target/classes ponto.Main < test/feed.txt > /tmp/ponto-baseline/session.txt`.
- Commit de separação por passo (git mv preserva histórico).

### T1 — Extrair `ponto.model`
- Criar `src/main/java/ponto/model/`; mover com `git mv`: `Entry, Report, Db, Repo, Service, Holidays, Parse, Str, Dur`.
- Extrair `Period` de `In.java` → `ponto/model/Period.java` (public record).
- Atualizar `package`/imports; abrir visibilidade só onde o compilador exigir.
- Validar: `mvn -o package` + `./test/smoke.sh` (PASS) + comparar session.txt com baseline.

### T2 — Extrair `ponto.view` + helpers novos
- Mover com `git mv`: `Out, In, Csv, Pdf, Xls, Json3` → `ponto.view`.
- Criar `ponto/view/Cols.java`: estáticos `dur(Entry)` (lógica atual de `durCol()`) e `hours(Entry)` (atual `hoursCol()`). Remover os dois métodos de `Entry` (que mantém `min()/hours()/dur()/closed()/open()/empty()` — domínio). Atualizar os 5 chamadores (`App.table`, `Csv`, `Pdf`, `Xls`, `Json` do T3). `stamp()` **fica** em `Entry` (rótulo de dados "08:00-12:00", usado em mensagens do Service; rigor combinado não exige DTO/mensagens limpas).
- Validar (mesma bateria do T1).

### T3 — Extrair `ponto.controller` + mover JSON
- Mover com `git mv`: `App, Web` → `ponto.controller`.
- Criar `ponto/view/Json.java` com `json(Entry)`/`jsonDay(Report.Day)` (corpos atuais de `Service`, chaves idênticas). Removê-los de `Service`. `Web` passa a usar `Json`.
- Validar (mesma bateria) + `curl` com diff contra os JSONs do baseline.

### T4 — Regra de dependência (rewire de App/Web) + composição
- `Main`: `Db db = new Db(...); Service svc = new Service(db);` injetar `new App(svc)` / `new Web(svc, port)`. `Service.registerOff()` chamado nos arranques de `App.run()` e `Web.start()` (substitui as chamadas soltas de `Holidays.extra`).
- `App`: remover campos `db` e `repo`; tudo via `svc`. Adições pequenas e sincronizadas ao `Service` (model):
  - `int count()`, `LocalDate min()`, `LocalDate max()`, `int countOn(LocalDate)`,
  - `List<Entry> recent(int n, int skip)` e `List<Entry> search(String q)` (se `list(...)` não cobrir com clareza),
  - `List<Entry> byDay(String pattern, int n, int skip)` e `int countDay(String pattern)` (para `4`/`d`),
  - `String dbPath()`, `String rawOff()`,
  - overload `setConfig(String who, String off, boolean vacuum, Consumer<String> bad)` para o `c config` do terminal continuar avisando datas `off` inválidas (o nome de saída/fluxo não muda; `setConfig` atual vira delegação ao novo).
  - Remover os acessores `Service.repo()` e `Service.db()` se só serviam a App/Web.
- `App.report(p)`/`export()` → `svc.report(p.from(), p.to())` (Head com db path/carimbo já vem do Service). Confirmações interativas de meia-noite/sobreposição continuam com `svc.inspect` + chamar `svc.update/create(..., confirm=true, ...)` após o usuário responder — sem mudar textos.
- `Web`: trocar `svc.db().file` no log de start por `svc.dbPath()`.
- Validar (mesma bateria) + diff de session.txt e dos JSONs contra baseline: **byte-identical** onde aplicável.

### T5 — Documentação e varredura final
- Atualizar a seção `## Layout` do README com os subpacotes (e nota da regra de dependência).
- `mvn -o package` limpo (sem warnings novos no `-Xlint:all`), `./test/smoke.sh` PASS, `./ponto` e `./ponto --serve` manuais nas 12 teclas do menu.
- `npm --prefix web run build` só se algo do jar for reempacotado (frontend intocado — rebuild do `public/` normalmente desnecessário).

## Riscos

- **Visibilidade**: o widening package-private → public é o maior efeito mecânico; expor o mínimo (compilador dita). Sem interfaces por camada — fora do rigor combinado.
- **Regressão de saída**: o smoke usa digest golden (`test/expected.txt`) e as chaves de API são contrato do `web/src/types.ts`. Qualquer byte de texto/chave diferente = erro do refactor, não ajuste do golden.
- **Lock**: duas instâncias de `Service` hoje teriam locks separados se Main não injetar; injetar um único Service elimina isso.
- **Deploy**: nada muda em pom.xml/launcher/Dockerfile (`ponto.Main` permanece); `deploy/deploy.sh` copia jar + compose sem alteração.

## Fora de escopo

- Frontend React (organização, refactors, new libs).
- Renomear classes, partir `App.java` (549 linhas), DTOs por rota, interfaces de camada, observer/mvc reativo.
- Novas funcionalidades ou mudanças de esquema SQLite.
