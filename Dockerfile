FROM eclipse-temurin:25.0.2_10-jre
WORKDIR /app
COPY target/ponto.jar /app/ponto.jar
# The PostgreSQL JDBC URL (user/password included) is injected by docker-compose
# from the server .env (PONTO_DB_URL). No default: the app refuses to boot without it.
EXPOSE 8080
# Um app, um usuário, um banco pequeno no Raspberry Pi 4B:
# - UseCompactObjectHeaders (JEP 519, Java 25): cabeçalho de objeto de 8 B (~10-20% menos heap).
# - SerialGC: menos memória nativa e menos threads que G1 para heap pequeno.
# - Initial/MaxRAMPercentage segue o mem_limit do container (384m -> ~58m..210m heap).
# - MaxMetaspaceSize, ReservedCodeCacheSize e Xss256k evitam crescimento silencioso fora do heap.
# - ExitOnOutOfMemoryError troca servidor zumbi por restart automático do compose.
# - Sem UsePerfData: sem arquivo de mmap em /tmp.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseCompactObjectHeaders", "-XX:InitialRAMPercentage=15", "-XX:MaxRAMPercentage=55", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m", "-Xss256k", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-jar", "/app/ponto.jar"]
CMD ["--serve", "--port=8080"]
