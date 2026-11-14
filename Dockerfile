FROM eclipse-temurin:25.0.2_10-jre
WORKDIR /app
COPY target/ponto.jar /app/ponto.jar
# The PostgreSQL JDBC URL (user/password included) is injected by docker-compose
# from the server .env (PONTO_DB_URL). No default: the app refuses to boot without it.
EXPOSE 8080
# Um app, um usuário, um banco pequeno: SerialGC gasta menos memória e menos
# threads que G1, e MaxRAMPercentage deixa o heap seguir o limite do container
# em vez de um -Xmx fixo. ExitOnOutOfMemoryError troca um servidor zumbi por um
# restart, que o compose faz sozinho; sem UsePerfData, sem arquivo em /tmp.
# Nas flags do ENTRYPOINT (e não em JAVA_TOOL_OPTIONS) para o JVM não imprimir
# "Picked up JAVA_TOOL_OPTIONS" a cada start.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-jar", "/app/ponto.jar"]
CMD ["--serve", "--port=8080"]
