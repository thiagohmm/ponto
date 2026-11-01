# Plano — Banco de horas mensal + PWA com gráficos

## Contexto

O `ponto` já tem um "banco de horas" parcial: `Service.overtime(q, filter)` (src/main/java/ponto/model/Service.java:281) subtrai a jornada (`cfg daily`, default 8h) de **todo** dia com batida fechada — inclusive sábados, feriados e dias off próprios. O card "extras / banco de horas" no Home do web (`filterOt`) já consome esse valor. Não existe: falta marcada, saldo mensal, plano de compensação, alerta de negativo, gráficos, PWA.

## Regras de domínio decididas com o usuário

- **Banco mensal**: o saldo começa em 0 no dia 1 de cada mês e zera no fim do mês (implicitamente; não há cfg nova de meta).
- **Saldo por dia** (dentro do mês) — uma regra só, por dia:
  - dia com **falta marcada** (vence tudo): `batido − jornada` — o dia entra na conta mesmo vazio
  - senão, dia off (fim de semana, feriado, facultativo ou off próprio; `Holidays.off(d) != ""`): `batido − 0` (crédito integral)
  - senão (dia útil), com batida fechada: `batido − jornada`
  - senão, dia útil vazio: ignora (não pune — a punição só existe se marcada como falta)
- **Falta** é flag do dia inteiro, nunca uma linha de batida. *Bordas* (pergunta descartada pelo usuário — adotada a recomendação, estilo "warning, never a wall"): marcar falta em dia que já tem batidas fechadas é no-op contábil → **aviso** "o dia já tem batidas (a falta não muda o saldo)"; marcar falta em dia off deduz a jornada → **aviso** amarelo com o motivo; nunca bloquear.
- **Plano até zerar**: `saldo / dias úteis restantes`, arredondado em minutos, **recalculado por dia** (a cada leitura). Janela: dias úteis a partir de **hoje (inclusive)** até o fim do mês; exclui dias off e faltas futuras (dia pago sai do plano). Saldo positivo → "X min a menos por dia"; saldo negativo → **alerta** de banco negativo + "X min a mais por dia". O último dia absorve o resto do arredondamento.
- **Histórico mensal**: mês fechado = saldo calculado sobre o mês inteiro; mês corrente = saldo até hoje. Últimos 12 meses na barra.
- **Exibição**: header do terminal + `5 sum` + linha nova no resumo do PDF e do XLS (com "faltas: N" quando houver) + card e gráficos no Home do web. CSV ganha uma linha final com o saldo do período (escolha explícita do usuário, contra recomendação).
- **Gráficos** (escolha do usuário): barras por dia do mês corrente com linha do plano + barras do saldo fechado dos últimos 12 meses. Biblioteca: **Recharts** (escolha do usuário, +~100 kB).
- **PWA**: manifest + service worker feitos à mão, sem plugin/dependência de build.
- **Entrega** (pedido do usuário): **deploy no Pi** `thiagohmm@192.168.0.8` via `./deploy/deploy.sh` e **commit** dos fontes depois.

## Tarefas

### 1. Modelo — falta e saldo
1. `model/Db.java`: adicionar à `SCHEMA`:
   ```
   CREATE TABLE IF NOT EXISTS dia_flag (dia TEXT PRIMARY KEY, flag TEXT NOT NULL DEFAULT 'falta')
   ```
   (`CREATE TABLE IF NOT EXISTS` — sem migração de dados; o `.db` já existente no Pi ganha a tabela no primeiro boot.)
2. `model/Service.java`:
   - `falta(LocalDate d)`, `void falta(LocalDate d, boolean on)`, `List<LocalDate> faltas()` — CRUD na `dia_flag`, sob o `lock`.
   - Reescrever `overtime(q, filter)` com a fórmula unificada por dia: falta vence tudo (`batido − jornada`); senão `Holidays.off(d)` não vazio → `batido − 0`; senão dia com batida fechada → `batido − jornada`; senão ignora. `Home` do web passa a refletir: trabalho de fim de semana vira crédito integral.
   - `long monthSaldo(YearMonth ym)` — saldo do mês (a fórmula acima itera **todos os dias do mês** até hoje, não só as linhas: faltas contam mesmo em dia sem batida). Para mês fechado, itera até o fim do mês.
   - `record Bank(YearMonth ym, long saldoMins, String saldoLabel, int dailyMins, int remaining, int planMins, String planLabel, boolean neg)` + `Bank bank()` para o mês corrente; dias úteis restantes contam de hoje (inclusive) ao fim do mês, sem off/falta futura.
3. `model/Report.java` + `Service.report()`: `Report` ganha `saldoMins/saldoLabel/faltas/planoLabel` do período (calculado em `Service`, que consulta `Holidays` e a `dia_flag`).

### 2. Terminal
4. `view/Out.java`: `home(...)` ganha uma linha `saldo do mês +5:02 · plano 40 min a menos/dia` (ou `! banco negativo −3:20 · plano 30 min a mais/dia` em amarelo).
5. `controller/App.java`:
   - Tecla nova **`f`** no menu/dispatch/help: `f` ou `f <dia>` alterna falta (`ok falta 03/11/2026 marcada` / `ok falta 03/11/2026 retirada`); `f lista` lista as faltas marcadas. Avisos (nunca bloqueio): dia com batidas fechadas → "o dia já tem batidas (a falta não muda o saldo)"; dia off → amarelo com o motivo.
   - `sum()`: linha `saldo período` + `plano` logo abaixo do TOTAL.
6. `view/Csv.java`: linha final `saldo;+5:02` (ou `;-3:20`) com o saldo do período do `Report`.
7. `view/Pdf.java` e `view/Xls.java`: linha no resumo: `Saldo do mês  +5:02   ·   plano 40 min a menos/dia` (mais `faltas: 2` quando houver).

### 3. API web
8. `controller/Web.java`:
   - `GET /api/bank?ym=YYYY-MM` → `{ ym, saldo, saldoMins, dailyMins, remaining, planMins, planLabel, neg, days: [{dia, dur, hours, falta, kind}], months: [{ym, saldo, saldoMins} × 12] }` — `days` alimenta o gráfico do mês, `months` o histórico.
   - `PUT /api/days/{dia}/falta` body `{ on: bool }`.
   - `GET /api/home` e `/api/sum` ganham `saldo/plan` fields; `GET /api/day` ganha `falta`.
9. `view/Json.java`: jsons novos (dias do banco, histórico mensal, falta em DayInfo).

### 4. Frontend (web/)
10. `npm --prefix web install recharts`.
11. `types.ts` / `api.ts`: tipos `Bank`, `BankDay`, `MonthSaldo`; `api.bank(ym?)`, `api.setFalta(dia, on)`.
12. `pages/Home.tsx`:
    - Card do banco no bloco `filter-sum` (ou logo abaixo): saldo do mês, plano/dia, destaque vermelho quando negativo.
    - Botão "falta" no cabeçalho de cada dia agrupado (toggle, com confirm).
    - Gráfico 1 (Recharts BarChart): minutos por dia do mês corrente, `ReferenceLine` = jornada − plano/dia (linha do plano). Fonte: `/api/bank` do mês corrente (não depende do filtro `dia` da tabela).
    - Gráfico 2 (Recharts BarChart): saldo dos últimos 12 meses, vermelho negativo / verde positivo.
13. `styles.css`: ajustes mínimos para os cards/gráficos (manter o estilo atual).

### 5. PWA
14. `web/public/icon.svg` (relógio + "P", cores do theme-color) + `web/public/icon-192.png`, `icon-512.png` e `apple-touch-icon` 180 gerados **uma vez** com `web/tools/Icon.java` (single-file, `java web/tools/Icon.java web/public`: BufferedImage 2D simples, sem dependência — Java 25 já é requisito do projeto); PNGs commitados, o gerador é fonte.
15. `web/public/manifest.webmanifest`: `name: Ponto`, `display: standalone`, `start_url: /`, `theme_color`, icons svg+png.
16. `web/public/sw.js` (~40 linhas): cache-first para `/assets/*` (são hashados), network-first para navegação e `/api/*` com fallback offline (a própria página mostra erro de rede como hoje).
17. `web/index.html`: `<link rel="manifest">`, `<link rel="apple-touch-icon">`, `<meta name="apple-mobile-web-app-capable">`.
18. `web/src/main.tsx`: registrar o SW só em produção (`import.meta.env.PROD`).

### 6. Testes e docs
19. `test/feed.txt`: incluir `f 12/10/2026` (falta num feriado) e `f lista`; `test/expected.txt` ganha as linhas de saldo/plano no header, sum e CSV.
20. `test/smoke.sh`: manter os mesmos passos (o digest cobre as saídas novas).
21. `README.md`: tecla `f` na tabela de keys, saldo/plano na seção Data, PWA na seção build/run.

### 7. Entrega — deploy no Pi e commit (pedido do usuário)
22. **Deploy** `thiagohmm@192.168.0.8`: `./deploy/deploy.sh` já faz tudo (react build → `mvn package` → rsync jar/Dockerfile/compose → `docker compose up --build -d` no Pi → espera `/api/home`). O jar novo já carrega o React/PWA, e o `.db` existente (`/home/thiagohmm/.local/share/ponto/ponto.db`) é preservado pelo bind-mount, com backup `cp -n` do script próprio. O script recusa enviar se houver um `ponto.db` solto em `target/` ou `web/` — não rodar o app com `--db` dentro do projeto durante os testes.
23. **Commit**: no branch atual `feat/web` (limpo hoje, com o plano em `.kilo/plans/` untracked). Cuidado com artefatos de build: o `npm run build` sobrescreve o placeholder commitado `src/main/resources/public/index.html` — restaurá-lo antes de commitar (`git checkout -- src/main/resources/public/index.html`), pois o jar em produção é construído pelo `deploy.sh` com o react dentro. `git add` **somente** fontes: `src/main/java`, `src/main/resources/public/index.html` (se restaurado), `web/src`, `web/public` (ícones/manifest/sw), `web/package.json` + `web/package-lock.json` (Recharts), `web/tools/Icon.java`, `web/index.html`, `test/` e `README.md` — nunca `target/`, `*.db` ou outro arquivo gerado; o `.kilo/plans/*.md` fica fora do commit. Mensagem curta no estilo do repo (`git log --oneline -10` para copiar o tom; ex.: "banco de horas mensal: falta, plano e saldo; web: gráficos + PWA"). **Nem push nem PR** — só commit local, como pedido.

## Riscos

- A mudança de semântica do `overtime()` altera o número do card já existente no web Home — proposital (pedida pelo usuário); o digest do smoke test precisa ser regenerado.
- Falta em dia futuro é suportada (o plano já sai do dia) — `monthSaldo` itera dias do mês, não linhas.
- `Holidays` é estado global mutável (`extra`): manter as chamadas `Holidays.extra(offDates(...))` onde hoje já existem, dentro do lock.
- Recharts aumenta o bundle ~100 kB — aprovado pelo usuário; auditar que a build continua no tamanho aceitável dentro do jar.
- iOS: `apple-touch-icon` PNG obrigatório (SVG não serve para o Safari).
- **Pi em produção**: `docker compose up --build` reinicia o container (curto downtime em `http://192.168.0.8:8090`); o `.db` antigo só ganha a tabela nova no primeiro boot (`CREATE TABLE IF NOT EXISTS`, sem migração). O `deploy.sh` recusa copiar `.db` solto — testes locais nunca salvam db dentro de `target/` ou `web/`.
- O deploy exige SSH/rede para `thiagohmm@192.168.0.8` disponível; se falhar, commit não deve ser feito sobre versão não validada no Pi (validar rede antes).

## Validação

```sh
mvn -o package
./test/smoke.sh                                  # PASS com saldo/plano/falta no digest
npm --prefix web ci && npm --prefix web run build  # tsc -b limpa, sai em src/main/resources/public
./ponto            # header: linha de saldo/plano; f 03/11; f lista; 5 sum com saldo
./ponto --serve    # Home com card do banco, botão falta e os dois gráficos

# deploy + validação no Pi (http://192.168.0.8:8090)
./deploy/deploy.sh
curl -fsS http://192.168.0.8:8090/api/bank        # saldo/plano/meses presentes
curl -fsS -o /dev/null -w '%{http_code} %{content_type}\n' \
  http://192.168.0.8:8090/manifest.webmanifest    # 200, application/manifest+json
curl -fsS -o /dev/null -w '%{http_code}\n' http://192.168.0.8:8090/sw.js   # 200

# commit local (branch feat/web), detalhe na tarefa 23
git status && git diff --stat
git restore src/main/resources/public/index.html          # placeholder commitado, não o build
git add src/main/java web/src web/public web/tools web/package.json web/package-lock.json \
        web/index.html test README.md
git commit
```

## Fora de escopo

- Login/multi-usuário, import CSV, backup automático, GitHub Actions, feriados de outras regiões.
- `git push` / PR / merge — o usuário pediu commit, não push.
