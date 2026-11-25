# Botão "Gerar justificativa" a partir da atividade do Kanban do dia

## Context

Ao registrar o ponto (formulário `/novo` e `/edit/:id`, `web/src/pages/Form.tsx`), o campo **Justificativa** é digitado à mão. O usuário quer um botão **"Gerar justificativa"** que preencha esse campo com os **títulos das tarefas que passaram no kanban naquele dia** pelas colunas **Em andamento**, **bloqueado** (cartões com bloqueio aberto) e **Feito**. Ex.: tarefa "Update versão java" movida para Em andamento em 25/11/2027 → ao registrar o ponto de 25/11/2027, clicar no botão coloca "Update versão java" na justificativa. Se não houve atividade no kanban naquele dia, o botão fica **desabilitado**. Os títulos ficam guardados num cache ("kafeine") com **expiração de 5 dias**.

### Descobertas da exploração

- **Não existe hoje registro de movimentação por coluna.** `kanban_card` só tem `crea`/`upd` (`Migrations.java:120`); `KanbanStore.moveCard()` (`KanbanStore.java:138`) atualiza `column_id`/`position` sem carimbar data/hora nem histórico. Mover um cartão **não** atualiza `upd` (só `updateCard` atualiza). Ou seja: hoje é impossível saber "quais cartões entraram em Em andamento no dia X".
- "Bloqueado" não é coluna: é estado do cartão (`kanban_block` com `cleared IS NULL`; `KanbanCard.blocked()`). Bloqueios têm `crea`/`cleared` com data — já dá para filtrar por dia.
- Colunas padrão semeadas: "A fazer", "Em andamento", "Feito" (`Migrations.seedKanbanColumns`); nomes protegidos contra exclusão; comparação normalizada em `KanbanStore.normalizeName/inProgress/done`.
- O Diário já agrega atividade do dia (`Diario.cardsOn` usa `crea LIKE 'dia%' OR upd LIKE ...`), mas isso pega edição de texto, não movimentação — insuficiente para o requisito.
- Não há Caffeine nem qualquer cache de títulos no projeto (backend Java puro + Javalin; frontend React). Cache existente no front: `localStorage` com guarda `safeStorage` (`web/src/vault/settings.ts:50`) e empresa ativa em `sessionStorage` (`Company.tsx`).
- Testes: backend JUnit em `test/ponto/model/KanbanTest.java` (Postgres via `TestDb`, relógio determinístico com `Time.using(Clock.fixed(...))` — ver `IntegrityTest.java:197`); frontend vitest em `web/test/Form.test.tsx` (mock de `api`). CI: `npm --prefix web test`, `mvn -B verify`.

## Approach (recomendado)

### Decisões confirmadas com o usuário

- Formato: **títulos, um por linha**, exatamente como no kanban.
- Clique: **acrescenta sem duplicar** o que já estiver digitado na justificativa.
- Cache "kafeine": **localStorage no navegador + histórico no servidor**, ambos com expiração de **5 dias**.
- Atividade do dia: **movimentação para Em andamento/Feito, bloqueio e também qualquer edição** (título/corpo/done) do cartão no dia.

### Backend — histórico de eventos do kanban com retenção de 5 dias

1. **Nova tabela `kanban_activity`** (migração v7 → v8 em `Migrations.java`):
   `id BIGSERIAL, company_id, card_id, title TEXT (título no momento do evento), kind TEXT ('moved' | 'blocked' | 'edited'), column_name TEXT (coluna do cartão no momento, normalizada), crea TEXT (carimbo padrão do projeto)`.
   - Índice `(company_id, crea)` para a consulta por dia e a poda.
   - FK para `kanban_card` com `ON DELETE CASCADE` (cartão apagado some do histórico).
2. **Gravar eventos**:
   - `KanbanStore.moveCard()`: quando `oldCol != columnId` **e** a coluna destino for "em andamento" ou "feito" (helpers existentes), insere evento `moved` com o título atual.
   - `KanbanStore.blockCard()`: insere evento `blocked` (equivale a "passou em bloqueado").
   - `KanbanStore.updateCard()`: insere evento `edited` (qualquer edição conta, conforme decisão do usuário).
   - Reordenar dentro da mesma coluna não gera evento.
3. **Poda com 5 dias**: ao gravar evento, `DELETE FROM kanban_activity WHERE company_id=? AND crea < to_char(now() - interval '5 days', 'YYYY-MM-DD')` — o "cache kafeine" expira em 5 dias, sempre no servidor (fonte da verdade).
4. **Endpoint** `GET /api/kanban/activity?dia=YYYY-MM-DD` em `KanbanRoutes`:
   - valida a data (`Service.parseDate`), filtra `crea LIKE 'dia%'`, deduplica por título preservando ordem, responde `{ dia, titles: [...] }`.
   - Títulos refletem o valor registrado no evento daquele dia (se o cartão foi renomeado depois, vale o título da época).
   - Cadeia: `KanbanRoutes → Service.kanbanActivity(dia) → KanbanApp → KanbanStore.activityOn(iso)`.

### Frontend — botão no formulário + cache local de 5 dias

5. **`api.ts`**: `kanbanActivity: (dia) => req<{ dia: string; titles: string[] }>("/kanban/activity?dia=" + ...)`.
6. **Novo módulo `web/src/kanbanCache.ts`** ("kafeine"): cache por empresa+dia em `localStorage` (chave `ponto-kanban-just:<companyId>:<dia>`), valor `{ titles, at }`; entradas com mais de **5 dias** são descartadas na leitura e numa varredura de limpeza; usar o padrão `safeStorage` (try/catch) de `vault/settings.ts`.
7. **`Form.tsx`**: ao mudar `dia` (e no mount), busca os títulos (cache primeiro; se ausente/expirado, chama a API e grava no cache). Botão **"Gerar justificativa"** junto ao campo Justificativa:
   - `disabled` quando a lista de títulos do dia está vazia (nada feito no kanban naquele dia);
   - ao clicar, **acrescenta** os títulos (um por linha) ao texto existente, pulando os que já estejam no texto (comparação por linha, case-insensitive);
   - recarrega quando o usuário troca o campo Dia (ex.: registrou ponto de 25/11 → títulos daquele dia);
   - para o dia corrente, revalida com a API a cada carregamento do formulário (atividade nova no kanban aparece); dias passados usam o cache até expirar.

## Files to modify

- `src/main/java/ponto/infra/Migrations.java` — v8: tabela `kanban_activity` + índice (fresh install e passo de upgrade 7→8).
- `src/main/java/ponto/infra/KanbanStore.java` — gravar eventos em `moveCard`/`blockCard`, poda de 5 dias, consulta `activityOn(iso)`.
- `src/main/java/ponto/app/KanbanApp.java` e `src/main/java/ponto/app/Service.java` — passthrough `kanbanActivity(LocalDate)`.
- `src/main/java/ponto/controller/KanbanRoutes.java` — rota `GET /api/kanban/activity`.
- `web/src/api.ts` — método `kanbanActivity`.
- `web/src/kanbanCache.ts` — **novo**: cache localStorage com TTL de 5 dias.
- `web/src/pages/Form.tsx` — botão + estado dos títulos do dia.
- Testes: `test/ponto/model/KanbanTest.java` (eventos, filtro por dia, escopo por empresa, poda), `web/test/Form.test.tsx` (botão habilitado/desabilitado, preenchimento), novo `web/test/kanbanCache.test.ts` (TTL).

## Reuse

- `KanbanStore.normalizeName/inProgress/done` (`KanbanStore.java:290-300`) — identificar colunas alvo.
- Padrão de carimbo `crea TEXT DEFAULT to_char(now(), 'YYYY-MM-DD HH24:MI')` e filtro `LIKE iso || '%'` já usado em `Diario.cardsOn` (`Diario.java:268`).
- `Service.parseDate` (`Service.java:420`) — validação do parâmetro `dia`.
- `Time.using(Clock.fixed(...))` (`model/Time.java`) — testes determinísticos de dia/poda.
- `safeStorage`/`safeJson` (`web/src/vault/settings.ts:50`) — padrão de acesso seguro ao localStorage.
- Estrutura de mock/teste de `web/test/Form.test.tsx` e classe de botão `.btn`/`.chip` já existentes em `styles.css`.

## Steps

- [x] Migração v8: criar `kanban_activity` (schema fresh + passo 7→8) com índice e FK cascade.
- [x] `KanbanStore`: inserir evento em `moveCard` (destino Em andamento/Feito), `blockCard` e `updateCard` (edição); podar registros com mais de 5 dias a cada escrita.
- [x] `KanbanStore.activityOn(iso)` + passthrough em `KanbanApp`/`Service` + rota `GET /api/kanban/activity` (dedupe de títulos).
- [x] Testes backend: mover para Em andamento/Feito gera evento; mover para A fazer/reordenar não gera; bloqueio e edição geram; filtro por dia com relógio fixo; isolamento por empresa; poda após 5 dias.
- [x] `web/src/kanbanCache.ts`: get/set por empresa+dia, TTL 5 dias, limpeza de expirados, tolerante a localStorage indisponível.
- [x] `api.kanbanActivity` + integração no `Form.tsx`: carregar títulos do dia (cache→API), botão "Gerar justificativa" desabilitado sem títulos, clique preenche a textarea sem duplicar.
- [x] Testes frontend: cache (TTL/expiração), Form (botão desabilitado sem atividade; clique acrescenta títulos sem duplicar; troca de dia recarrega).
- [x] Rodar `npm --prefix web test`, `npm --prefix web run build` e `mvn -B verify`.

## Verification

1. **Backend**: `mvn -B verify` (sobe Postgres de teste via `TestDb`) — novos casos em `KanbanTest`.
2. **Frontend**: `npm --prefix web test` (vitest) e `npm --prefix web run build` (tsc + vite).
3. **Manual** (`docker-compose up` ou dev): criar cartão "Update versão java", arrastar para Em andamento; abrir `/novo` com o dia de hoje → botão habilitado; clicar → textarea contém "Update versão java"; trocar o Dia para uma data sem atividade → botão desabilitado; conferir no DevTools a chave `ponto-kanban-just:*` no localStorage e que entradas antigas (>5 dias) somem.
