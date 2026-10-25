#!/bin/zsh
# Build on this machine, publish jar+compose to the Pi. Never copies a .db.
set -euo pipefail
here="${0:A:h:h}"
host="${PONTO_HOST:-thiagohmm@192.168.0.8}"
remote="${PONTO_REMOTE:-/home/thiagohmm/ponto-web}"
dbdir="${PONTO_DBDIR:-/home/thiagohmm/.local/share/ponto}"

cd "$here"

if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    [ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$HOME/.asdf/installs/java/temurin-25.0.1+8.0.LTS"
fi
mvn="${MVN:-}"
if [ -z "$mvn" ]; then
    for c in /opt/homebrew/bin/mvn /usr/local/bin/mvn "$(command -v mvn || true)"; do
        case "$c" in *asdf*|*/shims/*) continue ;; esac
        [ -x "$c" ] && { mvn="$c"; break; }
    done
fi

if [[ -e target/ponto.db || -e web/ponto.db ]]; then
  print -u2 "x   recusou enviar .db (o banco da web é o do servidor)"
  exit 1
fi

print "  react"
npm --prefix web install
npm --prefix web run build

print "  jar"
"$mvn" -B -f pom.xml package -q

print "  rsync jar Dockerfile compose -> $host:$remote"
ssh "$host" "mkdir -p $remote/target $dbdir"
rsync -az --exclude '*.db' --exclude '*.db-*' \
  Dockerfile docker-compose.yml .dockerignore "$host:$remote/"
rsync -az --exclude '*.db' --exclude '*.db-*' \
  target/ponto.jar "$host:$remote/target/"

print "  backup local do banco que já está no Pi"
ssh "$host" "test -f $dbdir/ponto.db || { echo 'x   $dbdir/ponto.db ausente' >&2; exit 1; }
  cp -n $dbdir/ponto.db $dbdir/ponto.db.bak-web-\$(date +%Y%m%d) || true
  cd $remote && docker compose up --build -d"

print "  wait"
for i in {1..90}; do
  if curl -fsS "http://192.168.0.8:8090/api/home" >/dev/null; then
    print "  ok  http://192.168.0.8:8090"
    curl -sS "http://192.168.0.8:8090/api/home"
    echo
    exit 0
  fi
  sleep 1
done
print -u2 "x   web não respondeu em :8090"
exit 1
