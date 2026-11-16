FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY build/quarkus-app/ ./

USER 1000:1000

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
