FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/ponto.jar /app/ponto.jar
ENV PONTO_DB=/data/ponto.db
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/ponto.jar"]
CMD ["--serve", "--port=8080"]
