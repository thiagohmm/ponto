# ponto

Controle de ponto no terminal: SQLite, Java 25, um comando, um arquivo de banco de dados,
três formatos de relatório. Feito para o hábito brasileiro " carto de ponto" — dia, entrada, saída,
justificativa — com datas como `dd/mm/aaaa` e horas decimais como `0.75`.

```
--------------------------------------------------------
  P O N T O
  Ana                   12 registros
  este mês 46:30   aberto 1   desde 20/05/2026
  /Users/ana/.local/share/ponto/ponto.db
--------------------------------------------------------
  1 novo    2 editar    3 excluir  4 listar
  5 soma    6 buscar    7 pdf     8 xls
  9 csv     d data      c config  x limpar
  ? ajuda   0 sair
--------------------------------------------------------
>
```

## Requisitos

* **Java 25** — as fontes são compiladas com `--release 25`.
* Maven, apenas para build; o jar produzido roda standalone.
* SQLite, PDFBox e POI já estão dentro do jar. Também está **jollyday**
  (`jollyday-core` + `jollyday-jackson`, cerca de 5.6 MB do jar de ~29 MB), que
  mantém o calendário brasileiro. Nada no app acessa a rede.

## Build e run

```sh
mvn -o package      # fat jar  ->  target/ponto.jar
./ponto             # roda direto do projeto
./install.sh      # jar -> ~/.local/share/ponto, launcher -> ~/.local/bin/ponto
ponto             # em qualquer lugar depois
```

O launcher encontra um runtime Java 25+ automaticamente (`$PONTO_JAVA`, então
`/usr/libexec/java_home -v 25`, então a instalação asdf, então `java` no PATH)
e adiciona `--enable-native-access=ALL-UNNAMED` para o driver do SQLite ficar silencioso.

## Dados

* Banco de dados: `$PONTO_DB`, default `~/.local/share/ponto/ponto.db` (ou `--db=path`).
* Uma tabela, `ponto`: `id, dia, tin, tout, just, crea`.
* Qualquer linha pode ser editada ou excluída a qualquer momento — nada é travado para "hoje".
* Uma linha pode ter um relógio (um ponto aberto) ou nenhum de todos (apenas uma nota).

## Teclas

| tecla | o que faz |
|-------|-----------|
| `1` | novo entrada: dia, entrada, saída, justificativa — os registros existentes do dia e seu total aparecem assim que você informa o dia, e um fim de semana ou diaferiado é indicado |
| `2` | editar uma entrada (`2` para escolher da lista, `2 7` para ir direto ao id 7) — mover um registro para um fim de semana ou feriado também avisa |
| `3` | excluir — pede **dia todo ou id** (`3`, `3 a`, `3 01/10`, `3 5 6 7`): linhas e total mostrados, um `y`, o menu volta |
| `4` | listar os registros mais recentes — ou por data: `4 2026`, `4 10/2026`, `4 out`, `4 01/10/2026`; `antigo` pageia, `total` é a soma acumulada |
| `5` | soma: totais por dia para um período |
| `6` | buscar nas justificativas (`6 doente`, ou `6` depois digita o texto) |
| `7` | exportar relatório PDF (resumo + detalhe) |
| `8` | exportar Excel real 97-2003 `.xls` (BIFF8, não xlsx) |
| `9` | exportar CSV (UTF-8 BOM, `;` separado, decimal sempre `.`) |
| `c` | config: nome do usuário, seus próprios dias de folga (`off`), caminho do banco de dados, vacuum |
| `d` | listar por data — pede um **dia, mês ou ano** (`d`, `d 2026`, `d out`) |
| `x` | limpar a tela |
| `?` | ajuda |
| `0` | sair |

A saída fica na tela. O menu é redesenhado *abaixo* do que a tecla imprimiu
(a tela é apagada uma vez, apenas na primeira pintura), então `4`, `5`, `6` e as
linhas `ok` ficam legíveis e no scrollback — `antigo` continua pageando até o
último registro ser mostrado. Pressione `x` quando quiser uma tela limpa.

### Listar por data

`4` e `d` usam o mesmo filtro. É combinado contra o dia de cada registro, então
uma palavra é suficiente:

| você digita | você obtém |
|-------------|------------|
| `4` · `4 50` | os registros mais recentes (`50` = quantas linhas) |
| `4 2026` | todos os registros de 2026 |
| `4 10/2026` · `4 2026-10` | outubro de 2026 |
| `4 out` · `4 out 2026` · `4 outubro` | outubro (este ano quando nenhum ano é dado) |
| `4 01/10/2026` · `4 2026-10-08` · `4 5out` | um dia |
| `4 hoje` · `4 y` · `4 -3` | hoje, ontem, três dias atrás |

Um número puro 1..999 significa *quantas linhas*, então para outubro escreva `10/2026` ou `out`.
Cada tela termina com `total` (a soma acumulada, linhas abertas excluídas) e `mostradas N de M`;
`antigo` traz o restante. Nada que combine diz isso: `nada em fev 2026`.

## Dias de folga

`1` e `2` imprimem uma linha amarela quando o dia não é um dia normal de trabalho:

```
  dia [10/10/2026] > 03/10/2026
  !   03/10/2026  SAT   fim de semana
  dia [10/10/2026] > 25/12/2026
  !   25/12/2026  FRI   feriado: Natal
  !   26/12/2026  SAT   feriado: Boxing Day   ·   fim de semana
  !   16/02/2026  MON   opcional: Carnaval segunda-feira
```

É um aviso, nunca uma barreira: pessoas trabalham fins de semana e feriados, e um
ponto perdido custa mais que um ponto no dia errado. `c config` lista os próximos
dias de folga e pede `off`, seus próprios dias de folga — `27/10/2026 31/12`, separados
por espaço, vírgula ou `;`.

O calendário vem de [jollyday](https://jollyday.focus-shift.de): a lista de países
para `BR` plus os códigos ISO 3166-2 `sp` (estado) e `csp` (cidade), então
09/07 e 20/11 contam como dias de São Paulo e 25/01 conta para a cidade. Cinco dias
são adicionados manualmente em `Holidays.java`, verificadas contra o calendário da cidade
(Prefeitura de São Paulo, Decreto 64.862/2025) e listados lá com o motivo:

| dia | o que é |
|-----|---------|
| 26/12 | Boxing Day, nacional, faltando no XML do país |
| Carnaval segunda-feira | o XML sabe apenas a terça-feira |
| 24/12 · 31/12 | véspera de natal, véspera de ano novo — dias de folga opcionais |
| 30/10 | Dia do Servidor (28/10 deslocado), opcional |
| Carnaval terça-feira · Quarta-feira de cinzas | o XML diz *público*, o decreto diz *facultativo* |

## Entrada

```
datas    08/10  ·  08/10/2026  ·  2026-10-08  ·  5out  ·  hoje  ·  y  ·  -3
horas    07:30  ·  07h30  ·  0730  ·  8  ·  agora
Enter    mantiene o valor mostrado entre colchetes
-        limpa o campo
```

## Variáveis de ambiente

| variável | significado |
|----------|-------------|
| `PONTO_DB` | caminho do banco de dados |
| `PONTO_USER` | nome impresso nos relatórios (útil em scripts) |
| `PONTO_JAVA` | runtime Java usado pelo launcher |
| `PONTO_CSV_SEP` | set para `,` para obter vírgulas em vez de ponto e vírgula |

## Testes

```sh
./test/smoke.sh
```

Replays `test/feed.txt` em um banco de dados descartável, exporta os três formatos,
lê de volta (`test/Verify.java` abre o PDF com PDFBox e a workbook com POI) e diff
um digest contra `test/expected.txt`. O relógio de geração, o diretório de teste e o
nome do usuário do OS são mascarados, então o arquivo golden hold em qualquer máquina.

Depois replay `test/feed-off.txt` e verifica os dias de folga na saída real:

```sh
./test/smoke.sh     # PASS  smoke   dias de folga + digest, session e arquivos em /tmp/ponto-smoke
```

| afirmado | por quê |
|----------|---------|
| `! 12/10/2026 MON feriado: Nossa Senhora de Aparecida` | 12 de outubro é um feriado nacional |
| `! 17/10/2026 SAT fim de semana` | o lado do fim de semana da mesma regra |
| `! 24/12/2026 THU opcional: Véspera de Natal` | o tier mostrado quando o dia é apenas facultativo |
| `ok #1 12/10/2026 08:00-12:00 4:00` | o ponto no feriado **é** salvo: um aviso, não uma barreira |
| `dia de folga 12/10/2026 …` | `c config` lê o mesmo calendário |
| *nada* em `13/10/2026` | uma terça-feira de trabalho fica silenciosa |

## Layout

```
src/main/java/ponto/
  Main        pontos de entrada, primeira exec, problemas não tratados
  App         o loop do menu, um método por tecla
  In          entrada digitada: datas, horas, ids, sim/não, texto livre
  Out         impressão: regras, colunas, cores apenas em um TTY
  Db          conexão, schema, a tabela `cfg`
  Repo        CRUD sobre a tabela `ponto`
  Entry       uma linha: dia, entrada, saída, justificativa (aberta / nota / fechada)
  Report      totais por dia e total geral, compartilhado pelos três exportadores
  Parse       texto de data e hora -> valores
  Holidays    fins de semana e os dias de folga no Brasil, visto de São Paulo
  Str, Dur    helpers de string e duração
  Csv, Pdf, Xls   os writers
  Ver         NOME e VERSÃO
test/         feed.txt, feed-off.txt, Verify.java, expected.txt, smoke.sh
```
