FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY . .

RUN ./gradlew assemble --no-daemon --max-workers=1 -x quarkusGenerateCode \
    -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m" \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /src/build/quarkus-app/ ./

USER 1000:1000

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
