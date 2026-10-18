#!/bin/zsh
# Build the fat jar and install ponto on the PATH (~/.local/bin).
set -e
here="${0:A:h}"
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
if [ ! -x "$mvn" ]; then
    print -u2 "x   maven not found (set MVN=/path/to/mvn)"
    exit 1
fi

bin="$HOME/.local/bin"
share="$HOME/.local/share/ponto"
mkdir -p "$bin" "$share"

"$mvn" -B -q -o -f "$here/pom.xml" package
cp "$here/target/ponto.jar" "$share/ponto.jar"
cp "$here/ponto" "$bin/ponto"
chmod +x "$bin/ponto"

print "  java      $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
"$bin/ponto" --version
print "  installed   $bin/ponto"
print "  database    \${PONTO_DB:-$HOME/.local/share/ponto/ponto.db}"
