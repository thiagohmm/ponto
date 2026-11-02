# Tolerância CLT (art. 58 §1º / Súmula 366) + tema claro/escuro + mês corrente

Data: 01/11/2026 · Projeto: `ponto` (Java 25 + Javalin + React/PWA em `web/`)

## Decisões confirmadas com o usuário

1. **Tolerância simétrica**: saldo do dia (`batido − jornada`) entre **−5 e +5 min → 0**
   (não soma nem desconta); passando de 5 min **em qualquer direção, os minutos
   inteiros contam** (7 min a mais → +7; 7 min a menos → −7). Base legal:
   art. 58 §1º da CLT ("variações de até 5 minutos não são descontadas nem
   computadas como hora extra") + Súmula 366 do TST. O limite diário de
   10 min é a soma de 5 min por marcação (entrada+saída); o app só tem uma
   variação por dia (batido vs jornada), então o gatilho efetivo é 5 min —
   documentar isso no README.
2. **Onde vale**: só nos caminhos `batido − jornada` (dia útil normal e dia off
   marcado **normal**). NÃO vale em falta (flag explícita do usuário vence
   — deduz a jornada de qualquer jeito) nem em dia off livre (banca tudo;
   não há jornada para tolerar).
3. **Tema**: escolha Sistema/Claro/Escuro; primeiro acesso segue o sistema
   (`prefers-color-scheme`); escolha salva no `localStorage`; botão lua/sol na
   toolbar da Home alterna na hora + opção completa na página Config.
4. **Mês corrente**: Home passa a exibir por padrão só o mês corrente; outros
   meses via filtro existente (input "dia, mês ou ano" + seletor de mês já
   presentes na toolbar). A página Soma também vira o mês corrente por padrão.
5. **Deploy**: `./deploy/deploy.sh` ao terminar (o script já faz: build web +
   jar, rsync e `docker compose up` em `thiagohmm@192.168.0.8`,
   http://192.168.0.8:8090).

## Tarefas

### 1. Backend — tolerância em `Service.java`

`src/main/java/ponto/model/Service.java`

- [ ] Constante pública `TOLERANCE_MINS = 5` (com javadoc citando art. 58 §1º e
  Súmula 366, ligação ao limite diário de 10 min e por que o gatilho aqui é o
  5º minuto).
- [ ] Helper privado estático `tolerated(long raw)`: `Math.abs(raw) <=
  TOLERANCE_MINS ? 0 : raw` (regra do 6º minuto: uma vez estourada a tolerância,
  todo o saldo do dia conta, incluindo os primeiros minutos).
- [ ] `saldoBetween` (linha ~362): aplicar `tolerated(...)` nos ramos
  `normal`-marcado e `batido − daily`(dia útil). Ramos de falta e dia off ficam
  intocados.
- [ ] `overtime(q, filter)` (linha ~313): aplicar a mesma helper nos mesmos dois
  ramos para os dois caminhos continuarem idênticos. Fatorar a contribuição do
  dia num único método usado pelos dois, se evitar duplicidade for limpo.
- [ ] Nada muda em `home()` (horas brutas do mês), `Report`, `Pdf/Xls/Csv` —
  eles só exibem o saldo, que passa a vir tolerado.

### 2. Backend/Home — mês corrente na web

`web/src/pages/Home.tsx`

- [ ] `useState` de `dia` inicia com o mês corrente `MM/YYYY` (ex.: "11/2026"),
  que o `Parse.like` já aceita — mesmo efeito de digitar hoje.
- [ ] Placeholder/valor do seletor de mês (`input.mes`) reflete ele; ao limpar o
  filtro, volta a "todos os registros" (comportamento atual).
- [ ] Placeholder do input de dia menciona "mês corrente por padrão".

`web/src/pages/Sum.tsx`

- [ ] Default `from` = 1º dia do mês corrente e `to` = hoje (em vez de
  `h.min/h.max`).

### 3. Tema claro/escuro (web)

- [ ] `web/src/theme.ts` (novo): lê/grava `localStorage["ponto.tema"]`
  ("claro" | "escuro" | "sistema"), aplica `data-tema` no `<html>`, escuta
  mudança de `prefers-color-scheme` quando em "sistema", e atualiza
  `<meta name="theme-color">`.
- [ ] `web/index.html`: script inline no `<head>` que aplica o tema salvo / do
  sistema antes da primeira pintura (sem flash).
- [ ] `web/src/styles.css`:
  - Adicionar paleta escura em `:root[data-tema="escuro"]` sobrepondo as vars
    existentes (`--paper`, `--ink`, `--muted`, `--line`, `--stamp`, `--ok`,
    `--warn`, `--card`, `--shadow`, `--v-*` do vault).
  - Trocar as cores hardcoded do claro por vars para o escuro funcionar de uma
    vez: body `#e8e8ed !important` → `var(--paper)`; `.tabs` `#f2f2f7`;
    `.toolbar input` `#e5e5ea`; `tr.day-head td` `#f2f2f7`; borda de `td`
    `#f0f0f2`; `.warn/.err/.ok` (bg/borda/texto); `code` `#f2f2f7`;
    `.vault` (`#fafafa`, `#fff`, `#dcdce2`, `color-mix(... #fff)`,
    `.vault-seg`, `.vault-status`, attach etc.). Zero mudança visual no claro.
- [ ] `Home.tsx`: botão lua/sol na toolbar (alterna claro↔escuro na hora,
  title "tema").
- [ ] `Config.tsx`: controle segmentado Sistema/Claro/Escuro com estado do
  `localStorage`.
- [ ] `web/public/sw.js`: conferir o nome do cache e fazer bump quando precisar
  para não servir shell velho depois do deploy.

### 4. Documentação

- [ ] `README.md`: seção "Banco de horas" ganha a regra da tolerância (±5 min
  simétrica por dia, 6º minuto fecha o saldo inteiro, 10 min/dia como teto legal
  de duas marcações, falta/dia off sem tolerância) + nota sobre tema e exibição
  do mês corrente.

### 5. Testes

- [ ] `test/Normal.java`: adicionar checks de tolerância com datas passadas
  fixas num mês vazio (ex.: 2015-01-05, segunda-feira útil, jornada 8h):
  `8:00-16:04` → saldo 0 (mais 4, tolerado); `8:00-16:07` → +7; `8:00-15:55`
  → 0 (menos 5, tolerado); `8:00-15:54` → −6. E um check de falta: dia com falta
  e 7:56 trabalhados → deduz a jornada (intocado pelo art. 58).
- [ ] `./test/smoke.sh` deve continuar PASS (o golden `expected.txt` não muda —
  nenhum dia do feed cai na faixa de tolerância).

### 6. Verificação local

- [ ] `npm --prefix web ci && npm --prefix web run build`
- [ ] `mvn -o package`
- [ ] `./test/smoke.sh` → PASS

### 7. Deploy (pedido explícito do usuário)

- [ ] `./deploy/deploy.sh` (host: `thiagohmm@192.168.0.8`, remote
  `/home/thiagohmm/ponto-web`, `docker compose up --build -d`, backup `-n` do db).
- [ ] Smoke pós-deploy: o próprio script verifica
  `curl -fsS http://192.168.0.8:8090/api/home`; abrir o app e conferir o
  toggle de tema, o mês corrente default e o saldo com tolerância.

## Riscos / observações

- A tolerância muda o histórico de saldos: dias que estavam em ±5 passam a
  valer 0 no banco (é o objetivo da lei).
- O vault de notas tem muitas cores hardcode; a lista do item 3 cobre todas as
  trocas conhecidas — conferir visualmente o modo escuro (lista + notas) antes
  do deploy.
- O terminal (`App.java`) usa os mesmos `Service.monthSaldo/report` — herda a
  tolerância de graça; nada a mudar lá.
- A tolerância só entra no saldo/banco; a duração exibida de cada linha e os
  totais de horas batidas ficam como estão hoje.
