# Correções da máscara/hora (rodada 2): apagar, validação, layout, pt-BR

Data: 01/11/2026 (mesma noite) · Refina o plano anterior (máscara + pares por
dia, já implantado). Quatro bugs reportados após o uso real.

## Bugs e decisões

1. **Backspace trava em `14:`** (`web/src/mask.ts`): `maskTime` força os
   dois-pontos quando restam 2 dígitos — o browser apaga e a máscara
   reaparece `:`, deletar nunca sai de `14:`. Correção: máscara ciente da
   deleção — `maskTime(raw, prev)`; quando `raw` encolheu frente ao valor
   anterior, **não re-insere o `:`** (≤2 dígitos → devolve os dígitos crus:
   `14:45` → `14:4` → `14:` → `14` → `1` → `""`). Digitando, o caminho de
   inserção continua formatando com `:`.
2. **Horas impossíveis (`89:99`)** — validação imediata, em duas partes:
   - Máscara corrige a hora sozinha (nunca exibe hora >23): quando o
     2-dígitos da hora estoura 23 (`29`, `83`...), vira hora de 1 dígito
     complementada (`29` → `02:9…`, `8…` → `08:…` como hoje). `89:99` pelo
     teclado acaba `08:99`.
   - Minuto >59 não tem como reparar enquanto digita (`9` pode virar `59`
     depois; `95`/`99` já é impossível): **borda vermelha viva** no campo
     completo e inválido (`timeOk` reutilizado → classe `bad`) + continuar
     bloqueando o Salvar (mensagens de entrada/saída já existentes).
     No blur o valor é mantido (nada de perder o que digitou).
3. **Botão verde quebra o layout** (`Form.tsx`): o `btn ok` sólido + rótulo
   longo "Salvar e lançar outra" estouram a linha no celular. Vira botão
   **neutro** (`btn`, sem Verde) com rótulo curto **"Lançar outra"** — os
   demais botões de ação da web já são neutros. `.btn.ok` do CSS não muda
   (o Confirm usa).
4. **"nothing typed" em inglês**: traduzir em `Service.java:145`
   (`throw new Bad(...)` → `"nada digitado"`) e na cópia do terminal
   `App.java:150` (`out.warn(...)` → `"nada digitado"`). Comparações de
   teste: smoke não tem expected com essa mensagem (verificado por grep) —
   golden intacto.

## Tarefas

- [ ] `web/src/mask.ts`: `maskTime(raw, prev?)` com os dois caminhos (inserção
  completa com `:`; caminho de deleção sem re-inserir `:`) e a regra de hora
  `>23 → vira 1 dígito com 0 à esquerda`.
- [ ] `web/src/pages/Form.tsx`: passar o valor anterior em cada `onChange`
  (`setTin(maskTime(v, tin))`); `className` com `bad` quando `timeOk(...)` dá
  erro; botão contínuo vira `<button className="btn">Lançar outra</button>`.
- [ ] `web/src/styles.css`: `.form input.hora.bad { border-color: var(--stamp);
  background: var(--stamp-soft); }`.
- [ ] `Service.java` e `App.java`: `"nothing typed"` → `"nada digitado"` (2
  lugares, uma string cada).
- [ ] Node unit-check da máscara (curso de digitar e apagar:
  14:45 deletando até "" sem travar; 29→02:9; 89:99→08:99 vermelho; 2205 →
  22:05; 830 → 08:30) e `npm --prefix web run build`.
- [ ] `./test/smoke.sh` PASS e `mvn -o -q package`.
- [ ] Deploy: `./deploy/deploy.sh` (thiagohmm@192.168.0.8 →
  http://192.168.0.8:8090) — fluxo padrão das rodadas anteriores.

## Riscos / observações

- Deleção no meio da string (apagar só o dígito do meio, ex. `1|4:45`) pode
  remontar o valor com outra partição — máscara minimalista sem gestão de
  caret; o caso de uso real (digitar ou apagar tudo do fim) fica correto.
- A correção da hora na máscara é silenciosa (`29…` → `02…`); o risco de
  surpresa é o mesmo já aceito no plano anterior (`2205` → `22:05`).
- Nada muda no banco, no Javalin (além do texto) ou no terminal além do
  texto da mensagem.
