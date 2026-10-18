#!/bin/zsh
# End-to-end check: builds the app, replays test/feed.txt into a throwaway
# database, exports pdf/xls/csv and diffs a digest against test/expected.txt.
set -e
setopt NULL_GLOB    # an empty "ponto-*.pdf" must not kill the script
here="${0:A:h}"; root="$here/.."
out=/tmp/ponto-smoke
rm -rf "$out"; mkdir -p "$out"

# any Java 25+ will do; the launcher's rule, reused here
# zsh does not export an assignment to a variable that was unset: say it out loud
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    [ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$HOME/.asdf/installs/java/temurin-25.0.1+8.0.LTS"
    [ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$(cd "$(dirname "$(command -v java || echo /nope)")"/.. 2>/dev/null && pwd || true)"
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    print -u2 "x   no JDK 25+ found (set JAVA_HOME=/path/to/jdk)"
    exit 1
fi
# an asdf shim would quietly pull in another JDK, so use a real maven binary
mvn="${MVN:-}"
if [ -z "$mvn" ]; then
    for c in /opt/homebrew/bin/mvn /usr/local/bin/mvn "$(command -v mvn || true)"; do
        case "$c" in *asdf*|*/shims/*) continue ;; esac
        [ -x "$c" ] && { mvn="$c"; break; }
    done
fi
# cp.txt goes stale the moment the pom gains a dependency, and the compile then
# fails on an import that looks perfectly fine. Rebuild it when either is true.
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

# ---- days off: 12/10/2026 is Our Lady of Aparecida (national) --------------
# The warning must appear, and the punch must still be saved: a weekend or a
# holiday is pointed out, never refused. A working day must stay silent.
DB2="$out/off.db"
PONTO_DB="$DB2" PONTO_USER=Ana "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CP" ponto.Main < "$here/feed-off.txt" > "$out/off.txt" 2>&1
off_ok=yes
for s in "!   12/10/2026  MON   holiday: Our Lady of Aparecida" \
         "!   17/10/2026  SAT   weekend" \
         "!   24/12/2026  THU   optional: Christmas Eve" \
         "matching  12/10/2026   ·   1 record(s)" \
         "total  4:00   4.00 h   ·   1 line(s)" \
         "ok  #1  12/10/2026  08:00-12:00  4:00"; do
    grep -qF -- "$s" "$out/off.txt" || { off_ok=no; print -u2 "x   missing: $s"; }
done
# `c config` lists the next days off from today on, so the exact dates drift
# with the calendar: count them instead of fixing them.
if [ "$(grep -cE '^    day off [0-9]{2}/[0-9]{2}/[0-9]{4}' "$out/off.txt")" -lt 4 ]; then
    off_ok=no
    print -u2 "x   config did not list the next days off"
fi
if grep -qF -- "!   13/10/2026" "$out/off.txt"; then
    off_ok=no
    print -u2 "x   13/10/2026 (a working tuesday) was warned about"
fi
if [ "$off_ok" = no ]; then
    print "FAIL  days off   (session in $out/off.txt)"
    exit 1
fi

if diff -u "$here/expected.txt" "$out/digest.txt" > "$out/diff.txt" 2>&1; then
    print "PASS  smoke   days off + digest, session and files in $out"
else
    print "FAIL  smoke   diff in $out/diff.txt"
    cat "$out/diff.txt"
    exit 1
fi
