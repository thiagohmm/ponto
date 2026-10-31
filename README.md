# ponto

Timesheet in the terminal: SQLite, Java 25, one command, one database file,
three report formats. Built for the Brazilian "punch card" habit — day, in, out,
justification — with dates as `dd/mm/yyyy` and decimal hours as `0.75`.

```
--------------------------------------------------------
  P O N T O
  Ana                   12 records
  this month 46:30   open 1   since 20/05/2026
  saldo do mês +5:02   ·   plano 40 min a menos/dia
  /Users/ana/.local/share/ponto/ponto.db
--------------------------------------------------------
  1 new     2 edit    3 delete  4 list
  5 sum     6 find    7 pdf     8 xls
  9 csv     d date    c config  f falta
  ? help    x clear   0 quit
--------------------------------------------------------
>
```

## Requirements

* **Java 25** — the sources are compiled with `--release 25`.
* Maven, only to build; the produced jar runs on its own.
* SQLite, PDFBox and POI are already inside the jar. So is **jollyday**
  (`jollyday-core` + `jollyday-jackson`, about 5.6 MB of the ~29 MB jar), which
  keeps the Brazilian calendar. Nothing in the app reaches the network.

## Build and run

```sh
mvn -o package      # fat jar  ->  target/ponto.jar
./ponto             # run straight from the project
./ponto --serve   # same database, in the browser (React)
./install.sh      # jar -> ~/.local/share/ponto, launcher -> ~/.local/bin/ponto
ponto             # anywhere afterwards
```

The terminal menu is unchanged. `--serve` starts Javalin on `$PONTO_PORT` (default 8090) and serves the React build from the jar. Both talk to the same SQLite file (`$PONTO_DB` / `--db=`).

The web app is a **PWA**: open `http://host:8090` in the phone's browser and "add to home screen" installs it like an app (standalone, icons, offline shell — `web/public/sw.js`, registered in production only).

```sh
npm --prefix web ci && npm --prefix web run build   # writes src/main/resources/public
mvn package
./ponto --serve --port=8090
```

On the LAN host `192.168.0.8` the existing `/home/thiagohmm/.local/share/ponto/ponto.db` is bind-mounted into Docker. The deploy script copies **only** the jar and compose files — never a `.db` from this machine.

```sh
./deploy/deploy.sh    # http://192.168.0.8:8090
```

The launcher finds a Java 25+ runtime by itself (`$PONTO_JAVA`, then
`/usr/libexec/java_home -v 25`, then the asdf install, then `java` on the PATH)
and adds `--enable-native-access=ALL-UNNAMED` so the SQLite driver stays quiet.

## Data

* Database: `$PONTO_DB`, default `~/.local/share/ponto/ponto.db` (or `--db=path`).
* One table, `ponto`: `id, dia, tin, tout, just, crea`.
* One flag table, `dia_flag`: `dia, flag` — the whole-day **falta** of the banco
  de horas (`f` / the web button). A flag of the day, never of a punch line.
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

The calendar comes from [jollyday](https://jollyday.focus-shift.de): the country
list for `BR` plus the ISO 3166-2 codes `sp` (state) and `csp` (city), so
09/07 and 20/11 count as São Paulo days and 25/01 counts for the city. Four days
are added by hand in `Holidays.java`, checked against the city's own calendar
(Prefecture of São Paulo, Decree 64.862/2025) and listed there with the reason:

| day | what it is |
|---|---|
| 26/12 | Boxing Day, national, missing from the country XML |
| Carnival Monday | the XML knows the Tuesday only |
| 24/12 · 31/12 | Christmas Eve, New Year's Eve — optional days off |
| Carnival Tuesday · Ash Wednesday | the XML says *public*, the decree says *facultative* |

(28/10 "Servant's Day" was removed: it is a public-servant day, not a private
holiday, and the bank must treat a punch there as a normal working day.)

## Banco de horas

The bank is **monthly**. One rule per day, never past today:

| the day | what counts |
|---|---|
| marked as **falta** (`f`) | batido − jornada — the day enters the math even empty, and it wins over every other rule |
| **day off** (weekend, holiday, optional, your `off`) | batido − 0 — everything worked is banked |
| working day with a closed punch | batido − jornada |
| empty working day | ignored (a missed punch is not a falta) |

`f` toggles the flag for the whole day (it lives in `dia_flag`, never on a punch
line). Marking a falta on a working day that already has closed punches is a
no-op for the balance and says so; marking one on a day off deducts the jornada
and warns in yellow — a warning, never a wall.

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
| `PONTO_DB` | database path |
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

Then it replays `test/feed-off.txt` and checks the days off on the real output:

```sh
./test/smoke.sh     # PASS  smoke   days off + digest, session and files in /tmp/ponto-smoke
```

| asserted | why |
|---|---|
| `! 12/10/2026 MON holiday: Our Lady of Aparecida` | 12 October is a national day off |
| `! 17/10/2026 SAT weekend` | the weekend side of the same rule |
| `! 24/12/2026 THU optional: Christmas Eve` | the tier shown when the day is only facultative |
| `ok #1 12/10/2026 08:00-12:00 4:00` | the punch on the holiday **is** saved: a warning, not a wall |
| `day off 12/10/2026 …` | `c config` reads the same calendar |
| *nothing* on `13/10/2026` | a working tuesday stays silent |

## Layout

```
src/main/java/ponto/
  Main        composition root: one Db, one Service, one UI per mode
  Problema, Bad, NeedConfirm, Eof   the shared failure types
  Ver         NAME and VERSION
  model/      domain: rules and SQLite, no printing
    Entry       one line: day, in, out, justification (open / note / closed)
    Report      totals per day and grand total, shared by the three exporters
    Db          connection, schema, the `cfg` table
    Repo        CRUD over the `ponto` table
    Service     the whole vocabulary both UIs may call (one lock)
    Holidays    weekends and the days off in Brazil, seen from São Paulo
    Parse       date and time text -> values
    Str, Dur    string and duration helpers
    Period      a closed date range
  view/       how things are rendered
    Out         printing: rules, columns, colours only on a TTY
    In          typed input: dates, times, ids, yes/no, free text
    Csv, Pdf, Xls   the writers
    Cols        the duration / decimal-hours table cells of one entry
    Json        the JSON views of the web API (keys of web/src/types.ts)
    Json3       Javalin's JSON mapper
  controller/ the two UIs; they talk to Service and render views, nothing else
    App         the menu loop, one method per key
    Web         the Javalin API
test/         feed.txt, feed-off.txt, Verify.java, expected.txt, smoke.sh
```

Dependency rule: `controller -> model`, `view -> model`, and `controller ->`
`view` when rendering; **the model never imports the view** — the JSON builders
and the table columns live in `view/` for that reason. The frontend (`web/`) is
unchanged: it is the browser view of the web mode.

