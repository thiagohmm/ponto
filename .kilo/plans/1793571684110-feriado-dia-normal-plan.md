# Pop-up de confirmação padronizado (modal estilo macOS)

## Objetivo

Substituir o `confirm()` nativo do navegador pelo do sistema: um modal React
padronizado, no visual do app (macOS-like: `-apple-system`, `--card`,
`--shadow`, bordas arredondadas). Escopo confirmado: **todas as caixas** —
7 usos:

- `web/src/pages/Home.tsx:146` — apagar registro (`confirm("apagar #id?")`)
- `web/src/pages/Home.tsx:161` — toggle falta/normal (chips + header do dia,
  via `toggleFlag`)
- `web/src/pages/Notes.tsx:298` — apagar arquivo anexado
- `web/src/pages/Notes.tsx:324` — apagar nota
- `web/src/pages/Notes.tsx:362` — apagar tag
- `web/src/pages/Notes.tsx:408` — apagar pasta

Depois dos builds passando, **deploy de novo em `thiagohmm@192.168.0.8`**
(instrução permanente do usuário: testes OK → `./deploy/deploy.sh`).

## Design do componente

- **Arquivo novo:** `web/src/ui/Confirm.tsx`
  - `ConfirmProvider` + contexto; modal montado uma vez no `App.tsx`
    (posicionamento `fixed`, sem portal — nenhum ancestral tem transform).
  - `useConfirm()` retorna `(opts) => Promise<boolean>`; os fluxos atuais
    viram `if (!(await confirm({ ... }))) return;` — mesmo comportamento,
    assíncrono, não-bloqueante.
  - `ConfirmOpts`: `{ title: string; message?: string; ok?: string; cancel?: string; tone?: "accent" | "ok" | "stamp" }`
    (`ok` default "Confirmar", `cancel` default "Cancelar", `tone` default "accent").
- **Visual (CSS em `web/src/styles.css`, usando as vars existentes):**
  - overlay `fixed inset 0; background rgba(0,0,0,.35); backdrop-filter blur(3px); z-index 100`
    (o `.tabs` usa z 20 — fica por baixo), centrado em flex;
  - card: `--card`, radius 16px, `--shadow`, largura `min(420px, 100vw - 32px)`,
    padding ~18/20px; `h3` título 15px; `p` mensagem em `--muted` 13px;
  - ações: linha à direita (estilo macOS) com `.btn` "Cancelar" (texto) e o
    botão de ação com o tom: `accent` → `.btn.primary`, `stamp` →
    `.btn.danger` (já existe), `ok` → `.btn.ok` (**classe nova, verde, ao lado
    de `.btn.danger` no CSS**).
- **Teclado e foco:** Esc = cancelar, Enter = confirmar, foco inicial no botão
  de ação; clique no overlay = cancelar. Acessível: `role="dialog"`,
  `aria-modal`.
- **Estados dos botões (mensagens concretas):**
  - falta ON (chips e header): título "Falta", msg
    `marcar a falta de {dia} — desconta a jornada do banco de horas`,
    ok "Marcar falta", tone `stamp`
  - falta OFF: título "Falta", msg `retirar a falta de {dia}`, ok "Retirar",
    tone `accent`
  - normal ON: título "Dia normal", msg
    `marcar {dia} como dia normal — as batidas pagam a jornada (não somam banco)`,
    ok "Marcar normal", tone `ok`
  - normal OFF: título "Dia normal", msg
    `desmarcar o dia normal de {dia} — as batidas voltam a somar ao banco`,
    ok "Desmarcar", tone `accent`
  - apagar registro: título "Apagar #id", msg "O registro {id} sai do banco.
    Sem volta.", ok "Apagar", tone `stamp`
  - Notas (4): título "Apagar", ok "Apagar", tone `stamp`, mensagens iguais às
    atuais (arquivo "…" · nota · tag "…" · pasta "…").

## Tarefas

1. `web/src/ui/Confirm.tsx`: provider + hook + view (estado interno do
   provider: `{ opts, resolve }` do diálogo aberto).
2. `web/src/App.tsx`: envolver o conteúdo (`shell`) com `<ConfirmProvider>`.
3. `web/src/styles.css`: `.overlay`, `.dialog`, `.dialog h3/p`,
   `.dialog-actions`, `.btn.ok` (nada de outros seletores muda).
4. `Home.tsx`: trocar os 2 `confirm()` por `useConfirm()` (o `setBusy`/
   treatment de erro do fluxo atual fica como está).
5. `Notes.tsx`: trocar os 4 `confirm()` (mesmo padrão).
6. Validação local: `npm --prefix web run build` (tsc + vite), 
   `./test/smoke.sh` PASS (backend intocado — digest não muda).
7. Deploy: `./deploy/deploy.sh` (host default `thiagohmm@192.168.0.8`,
   rebuild do container + health em `:8090`).
8. Verificação ao vivo: bundle novo servido (hash do asset muda no `index.html`)
   e conferência visual do modal nos cliques falta/normal (usuário válida).

## Fora de escopo

- Toasts/nítidos do resultado (o `warn` pós-ação continua onde está: linha
  `.warn` da home).
- Mudança no backend, testes Java ou textos dos warns da API.
- Diálogos na tela do terminal.

## Riscos

- Overlay cobre a tab bar (z 100 > 20) — intencional, scroll do fundo pode
  "vazar": travar scroll em `body` enquanto aberto (`overflow: hidden`).
- PWA cache: nova hash do asset — um refresh traz a versão nova (sw.js é
  network-first para navegação).
- Nenhum risco de dados: mudança só no front.

## Done

- Nenhum `confirm(` nativo restante em `web/src` (grep zerado).
- Modal padronizado em todas as caixas de confirmação, com Esc/Enter/clique
  no overlay funcionando.
- tsc + vite build limpos, smoke PASS, deploy em `192.168.0.8:8090` atualizado.
