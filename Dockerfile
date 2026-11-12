FROM eclipse-temurin:25.0.2_10-jre
WORKDIR /app
COPY target/ponto.jar /app/ponto.jar
# The PostgreSQL JDBC URL (user/password included) is injected by docker-compose
# from the server .env (PONTO_DB_URL). No default: the app refuses to boot without it.
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/ponto.jar"]
CMD ["--serve", "--port=8080"]
