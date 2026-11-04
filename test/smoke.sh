#!/bin/zsh
# Verificação end-to-end: build do app, replay test/feed.txt em um banco
# descartável, export pdf/xls/csv e diff um digest contra test/expected.txt.
set -e
setopt NULL_GLOB    # um "ponto-*.pdf" vazio não deve matar o script
here="${0:A:h}"; root="$here/.."
out=/tmp/ponto-smoke
rm -rf "$out"; mkdir -p "$out"

# qualquer Java 25+ serve; a regra do launcher, reutilizada aqui
# zsh não exporta uma atribuição para uma variável que foi unset: diga em voz alta
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    [ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$HOME/.asdf/installs/java/temurin-25.0.1+8.0.LTS"
    [ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$(cd "$(dirname "$(command -v java || echo /nope)")"/.. 2>/dev/null && pwd || true)"
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    print -u2 "x   nenhum JDK 25+ encontrado (set JAVA_HOME=/path/to/jdk)"
    exit 1
fi
# um shim asdf silenciosamente puxaria outro JDK, então use um binário maven real
mvn="${MVN:-}"
if [ -z "$mvn" ]; then
    for c in /opt/homebrew/bin/mvn /usr/local/bin/mvn "$(command -v mvn || true)"; do
        case "$c" in *asdf*|*/shims/*) continue ;; esac
        [ -x "$c" ] && { mvn="$c"; break; }
    done
fi
# cp.txt fica obsoleto no momento que o pom ganha uma dependency, e o compile então
# falha em um import que parece perfeitamente bem. Rebuild quando qualquer um for true.
if [ ! -f "$root/target/cp.txt" ] || [ "$root/pom.xml" -nt "$root/target/cp.txt" ]; then
    (cd "$root" && "$mvn" -B -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt) \
      || (cd "$root" && "$mvn" -B -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt)
fi
CP="$root/target/classes:$(cat "$root/target/cp.txt")"

cd "$root"
"$JAVA_HOME/bin/javac" -Xlint:none -cp "$CP" -d target/classes $(find src/main/java -name '*.java')
"$JAVA_HOME/bin/javac" -d target/test-out -cp "$CP" test/Verify.java

DB="$out/ponto.db"
PONTO_DB="$DB" PONTO_USER=Ana "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CP" ponto.Main < "$here/feed.txt" > "$out/session.txt" 2>&1
mv -f ponto-*.pdf ponto-*.xls ponto-*.csv "$out/"

"$JAVA_HOME/bin/java" -cp "$CP:$root/target/test-out" ponto.Verify \
    "$out"/ponto-*.pdf "$out"/ponto-*.xls "$out"/ponto-*.csv "$DB" > "$out/digest.txt" 2>&1

# ---- dias de folga: 12/10/2026 é Nossa Senhora de Aparecida (nacional) --------------
# O aviso deve aparecer, e o ponto deve ser salvo: um fim de semana ou um
# feriado é indicado, nunca recusado. Um dia de trabalho deve ficar silencioso.
DB2="$out/off.db"
PONTO_DB="$DB2" PONTO_USER=Ana "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CP" ponto.Main < "$here/feed-off.txt" > "$out/off.txt" 2>&1
off_ok=yes
for s in "!   12/10/2026  MON   feriado: Nossa Senhora de Aparecida" \
         "!   17/10/2026  SAT   fim de semana" \
         "!   24/12/2026  THU   opcional: Véspera de Natal" \
         "matching  12/10/2026   ·   1 registro(s)" \
         "total  4:00   4.00 h   ·   1 linha(s)" \
         "ok  #1  12/10/2026  08:00-12:00  4:00"; do
    grep -qF -- "$s" "$out/off.txt" || { off_ok=no; print -u2 "x   faltando: $s"; }
done
# `c config` lista os próximos dias de folga a partir de hoje, então as datas exatas drift
# com o calendário: conte-os ao invés de fixá-los.
if [ "$(grep -cE '^    dia de folga [0-9]{2}/[0-9]{2}/[0-9]{4}' "$out/off.txt")" -lt 4 ]; then
    off_ok=no
    print -u2 "x   config não listou os próximos dias de folga"
fi
if grep -qF -- "!   13/10/2026" "$out/off.txt"; then
    off_ok=no
    print -u2 "x   13/10/2026 (uma terça-feira de trabalho) foi avisado"
fi
if [ "$off_ok" = no ]; then
    print "FAIL  dias de folga   (session em $out/off.txt)"
    exit 1
fi

if diff -u "$here/expected.txt" "$out/digest.txt" > "$out/diff.txt" 2>&1; then
    print "PASS  smoke   dias de folga + digest, session e arquivos em $out"
else
    print "FAIL  smoke   diff em $out/diff.txt"
    cat "$out/diff.txt"
    exit 1
fi
