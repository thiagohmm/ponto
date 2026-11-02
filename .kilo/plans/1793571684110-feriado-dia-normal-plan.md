# Feature: toggle "dia normal" nos dias off (feriado/weekend/off) + deploy

## Objetivo

Todo dia que o app marca como dia off (weekend, `holiday:`, `optional:`, `off` do usuário) ganha um toggle **"dia normal"** na web, como o botão "falta" de hoje:

- **Flag desmarcada (default):** regra atual — tudo o que foi batido no dia vira banco de horas (`batido − 0`).
- **Flag marcada:** o dia conta exatamente como um dia útil normal — `batido − jornada` (10h batidas → +2:00 no banco; 8h → 0; dia vazio → ignorado, 0). Também passa a contar como dia útil nos "dias úteis restantes" do plano do mês.
- **Precedência:** a falta continua ganhando de tudo (mesmo primeiro em toda cadeia de regras).
- Depois dos testes passando, **deploy em `thiagohmm@192.168.0.8`** com o script existente `./deploy/deploy.sh` (host já é o default dele, serviço em `http://192.168.0.8:8090`).

## Decisões confirmadas com o usuário

1. Flag marcada = **batido − jornada** (não é crédito de jornada). Dia vazio não contribui, muda só o plano/remaining.
2. Escopo do toggle: **todos os dia off** (weekend incluso), não só feriado.

## Decisões de implementação

- Flag nova no schema existente `dia_flag(dia, flag)`: `flag = 'normal'` — sem migration, a tabela já aceita qualquer flag.
- Default desmarcado **não pode** alterar o cálculo → o digest de `test/expected.txt` não muda.
- Fora de escopo: menu do terminal (feature só web), telas Sum/Form/Export, mudança nos writers PDF/XLS/CSV (o saldo deles já sai corrigido automaticamente, pois usam `saldoBetween`).

## Tarefas — ordem

1. **`src/main/java/ponto/model/Service.java`** — espelhar a API de falta com a flag "normal":
   - `normal(LocalDate d)` → `repo.flagOn(d, "normal")`; `normal(LocalDate d, boolean on)` → `flagSet`/`flagClear`; `normais()` → `repo.flags("normal", "%")`.
   - `normalWarn(LocalDate d)` (estilo `faltaWarn`): dia com falta → "o dia também tem falta — a falta ganha"; dia com `Holidays.off(d)` vazio → "o dia não é dia off (sem efeito)"; senão "".
   - Regra do banco (em **`saldoBetween`** e **`overtime`** — mesmas regras, um por dia), carregar `Set<LocalDate> nm = new HashSet<>(repo.flags("normal", "%"))` e inserir entre falta e off:
     ```java
     if (fl.contains(d)) sum += m - daily;
     else if (nm.contains(d)) { if (mins.containsKey(d)) sum += m - daily; }  // marcado: dia útil normal
     else if (!Holidays.off(d).isEmpty()) sum += m;                          // day off: tudo banco
     else if (mins.containsKey(d)) sum += m - daily;
     ```
   - **`bank()`** — `remaining`: pular o dia só se `(off && !nm.contains(d)) || fl.contains(d)`; dia off marcado como normal passa a contar como dia útil.
   - **`bankDays()`** — record `BankDay` ganha `boolean normal`; `kind` do dia off marcado = `"work"` (barra azul no gráfico).
2. **`src/main/java/ponto/controller/Web.java`**:
   - `PUT /api/days/{dia}/normal` (espelho do handler `falta`): body `{ "on": true|false }`, resposta `{ok, dia, diaIso, normal, warn}` (warn = `svc.normalWarn(d)` só quando ligar).
   - `GET /api/day`: adicionar `"normal"` (`svc.normal(d)`).
   - `GET /api/entries`: adicionar `"normais"` (lista de dias ISO, espelho de `"faltas"`).
3. **`src/main/java/ponto/view/Json.java`** (contrato de `types.ts` — só CHAVES NOVAS, nada quebrado):
   - `json(Entry)`: adicionar `"off"` = `Holidays.off(e.dia())` (o handler de list já chama `svc.list` antes, então o cache de `Holidays.extra` está aquecido).
   - `jsonBankDay`: adicionar `"normal"`.
4. **`web/src/types.ts`**: `Entry.off: string`; `EntryList.normais: string[]`; `DayInfo.normal: boolean`; `BankDay.normal: boolean`.
5. **`web/src/api.ts`**: `setNormal(dia, on)` → `PUT /days/{dia}/normal` (espelho de `setFalta`).
6. **`web/src/pages/Home.tsx`**:
   - State `normais: Set<string>` carregado de `list.normais`; `DayGroup` ganha `off` (do primeiro entry do grupo).
   - No day-head-row, ao lado do botão falta: botão **"normal"** / `normal ✓`, visível somente quando `g.off !== ""`; `title="dia normal: as batidas pagam a jornada (não somam banco de horas)"`; confirm + warn em `msg`, como no `toggleFalta`.
   - Cor do gráfico: nada a fazer — `kind="work"` do backend já deixa a barra azul (`dayColor`).
7. **`web/src/styles.css`**: ao lado de `.btn.falta-on` (linha ~202), adicionar `.btn.normal-on` (mesma estrutura; cor neutra/verde `var(--ok)` para distinguir da falta).
8. **Testes**:
   - Novo `test/Normal.java` (harness que roda com o mesmo classpath do `Verify`, estilo do smoke): db em `/tmp`, `Service` direto, datas **passadas fixas** (ex.: 25/12/2024 feriado nacional, um sábado qualquer 2024) porque `saldoBetween` nunca passa de hoje. Casos:
     a) feriado com batida 08:00–18:00: desmarcado → `+10:00`; marcado → `+2:00`.
     b) dia off marcado sem batidas → não muda o saldo (contribui 0).
     c) marcado + falta no mesmo dia → a falta ganha (dia vazio → `−8:00`).
     d) remaining: com `YearMonth.now()`, marcar como normal os dias off que restam do mês e conferir que `remaining` aumenta (comparação relativa, à prova de fim de mês).
   - **`test/smoke.sh`**: compilar `Normal.java` junto do `Verify.java` e rodar após o digest; manter `PASS` na saída. `expected.txt` e `feed*.txt` ficam intactos.
9. **Validação local, nesta ordem, tudo PASS antes de partir pro deploy**:
   - `./test/smoke.sh`
   - `npm --prefix web run build` (tsc -b + vite; valida os tipos novos)
   - (o próprio deploy roda `mvn package`, mas conferir build do jar isolado se houver dúvida)
10. **Deploy** — `./deploy/deploy.sh`, que já faz: `npm ci/build` + `mvn package`, recusa-se a enviar `.db`, rsync de `Dockerfile`+`docker-compose.yml`+`ponto.jar` para `thiagohmm@192.168.0.8:/home/thiagohmm/ponto-web`, backup do db remoto (`ponto.db.bak-web-<data>`), `docker compose up --build -d` e health check em `:8090/api/home`.
11. **Verificação pós-deploy** (sem tocar no banco real sem querer): `PUT /api/days/<algum feriado real>/normal {"on":true}` → `GET /api/bank` → conferir saldo/remaining recalculados → `{"on":false}` para desfazer. Checar `GET /api/entries` (chaves novas) e o toggle na UI.

## Riscos

- Digest do smoke: só muda se a regra default (desmarcada) for alterada — os casos de teste (a–d) usam flag explícita, então `expected.txt` continua igual.
- Deploy reinicia o container (segundos de downtime); script espera até 90s por health.
- SSH precisa da chave do host carregada; lang do remote é Fi (Pi). `_só se falhar:` re-running `deploy.sh` é idempotente.
- `Holidays.off()` no `json(Entry)` é custo O(1) (map em cache por ano) — sem risco de performance no list de 500 linhas.

## Done

- Toggle "dia normal" funcionando em dia off na web, cálculo batido−jornada quando marcado, banco intocado quando desmarcado, falta com precedência.
- `./test/smoke.sh` PASS com o novo harness.
- `http://192.168.0.8:8090` servindo a versão nova com o toggle visível.
- Sem commit/push (não foi pedido); working tree fica com a feature pronta, deploy roda da tree.
