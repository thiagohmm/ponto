# Corrigir a largura da listagem por data (`d`)

## Objetivo

Alinhar as colunas da tabela exibida por `d` (e pelas demais telas que usam a mesma tabela) para que caibam em um terminal de 80 colunas, sem quebra de linha nem desalinhamento.

## Diagnóstico

Em `src/main/java/ponto/App.java`:

- `JUST_W = 34` e as larguras em `cols()` são `{5, 11, 4, 6, 6, 7, 34}`.
- Com 6 separadores de 2 espaços, cada linha da tabela tem **85 caracteres**.
- `out.note()` adiciona 4 espaços de recuo, totalizando **89 colunas** na tela.
- A linha separadora usa `"-".repeat(76)`, ficando **9 caracteres menor** que o cabeçalho e as linhas de dados (`App.java:414-432`).

Resultado: em terminal de 80 colunas a tabela quebra e o separador não acompanha as colunas.

## Decisão

Padronizar a tabela em **76 caracteres de conteúdo** (4 de recuo + 76 = 80 colunas na tela) e derivar o separador do próprio cabeçalho.

Novas larguras:

| Coluna | Largura |
|---|---:|
| ID | 5 |
| DATE | 10 (`dd/mm/yyyy`) |
| DAY | 3 (`SUN`..`SAT`) |
| IN | 5 (`HH:MM`) |
| OUT | 5 |
| DUR | 5 (`0:45`, `open`, `note`) |
| JUSTIFICATION | 31 |

Soma: `5 + 10 + 3 + 5 + 5 + 5 + 31 + (6 × 2) = 76`.

A justificativa continua sendo cortada na coluna e o texto completo continua sendo impresso abaixo, com `· `, usando `Str.wrap(..., 74)` — 4 de recuo + 2 do marcador + 74 = 80.

## Passos

1. Em `App.java`, alterar `JUST_W` de `34` para `31`.
2. Substituir as larguras fixas de `cols()` por uma constante única, por exemplo:
   ```java
   private static final int[] COL_W = {5, 10, 3, 5, 5, 5, JUST_W};
   ```
   e usar `COL_W[i]` no lugar do array local `{5, 11, 4, 6, 6, 7, JUST_W}`.
3. Em `table()` (`App.java:414`), montar o cabeçalho uma vez e usar o comprimento dele no separador:
   ```java
   String head = cols("ID", "DATE", "DAY", "IN", "OUT", "DUR", "JUSTIFICATION");
   out.note(head);
   out.note("-".repeat(head.length()));
   ```
   Isso elimina o `76` mágico e garante que cabeçalho, separador e linhas tenham sempre o mesmo tamanho.
4. Manter `Str.wrap(e.just(), 74)` inalterado.
5. Não alterar PDF, XLS, CSV nem a lógica de filtragem por data; a correção é apenas de renderização no terminal.

## Validação

1. Compilar: `mvn -o package`.
2. Rodar o teste existente: `test/smoke.sh` deve continuar passando.
3. Teste manual com banco de teste:
   - abrir o app, criar alguns registros com justificativas curtas e longas;
   - pressionar `d 2026` e `d 05/2026`;
   - confirmar que:
     - todas as linhas da tabela têm no máximo 80 colunas;
     - cabeçalho, separador e linhas de dados terminam na mesma coluna;
     - `JUSTIFICATION` alinha com os valores;
     - justificativas longas aparecem cortadas na tabela e completas abaixo, com `· `, sem passar de 80 colunas;
     - o menu continua sendo redesenhado abaixo sem apagar a listagem.
4. Confirmar que as telas que reutilizam `table()` — `1` (dia já existente), `2`, `3`, `4` e `6` — continuam alinhadas.

## Fora de escopo

- Tradução dos rótulos (`BY DATE`, `ID`, `DATE`, etc.).
- Mudança do conteúdo da listagem (linha a linha versus resumo).
- Suporte a caracteres de largura dupla (CJK/emoji) em `Str.fix`.
