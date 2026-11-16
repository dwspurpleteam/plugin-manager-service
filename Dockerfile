FROM --platform=$BUILDPLATFORM golang:1.27.4-alpine3.24 AS protobuf-tools

ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=linux GOARCH=$TARGETARCH go install github.com/easyp-tech/easyp/cmd/easyp@v0.17.0 \
    && mkdir /out \
    && cp "$(find /go/bin -type f -name easyp -print -quit)" /out/easyp

FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY --from=protobuf-tools /out/easyp /usr/local/bin/easyp
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
