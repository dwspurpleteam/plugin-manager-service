# plugin-manage-service

Plugin Manager is the control plane for plugin activations in calls. It discovers
and caches manifests, exposes signal routes and input media requirements, monitors
availability and retains state history. It does not forward signals or media.

Schema, package layout and public contracts are documented in
[docs/plugin-manager-contracts.md](docs/plugin-manager-contracts.md).

Unit tests are located in `src/test/kotlin` (`./gradlew test`). JVM integration tests
are located in `src/integrationTest/kotlin` (`./gradlew integrationTest`) and use
isolated infrastructure. Run both together with `./gradlew test integrationTest`.

## Configuration and first activation

Provide PostgreSQL and Redis and configure at least one registry:

```properties
plugin-manager.registries.example-registry.url=https://registry.example.com
```

Database credentials use the `DB_*` variables described below. Redis uses
`REDIS_URL` (default `redis://localhost:6379`). Registry URLs and all `plugin-manager.*`
policies can be set in `application.properties`, via Java system properties before
`-jar`, or through the corresponding Quarkus/SmallRye environment overrides.
Timeout, concurrency, retry, TTL and retention defaults are listed in the contract
document. Startup rejects invalid limits and incompatible lease/deadline settings.

The `local` profile connects to the configured PostgreSQL data source at
`localhost:5432/plugin_manager` with the dedicated application and migration roles:

```shell
./gradlew quarkusDev -Dquarkus.profile=local
```

```shell
./gradlew assemble
java -Dplugin-manager.registries.example-registry.url=https://registry.example.com \
  -jar build/quarkus-app/quarkus-run.jar
```

```shell
curl -i -H 'Content-Type: application/json' http://localhost:8080/v1/activations \
  -d '{"callId":"call-10","pluginId":"example-plugin","requestedBy":"user-12","idempotencyKey":"activate-1"}'
```

Registration returns 202 with an activation ID without waiting for a registry or
healthcheck. Poll `/v1/activations/{activationId}`; only ACTIVE permits reading
`/signal-route` and `/media-types`. Add `pluginVersion` for an exact version; omitted
version selects the first suitable registry's current latest and pins it for that
activation. Reuse a key only for an identical original request.

The OpenAPI document is available at `/q/openapi`, and Swagger UI is available at
`/swagger-ui` in development and packaged applications. Each build also writes
`openapi.yaml` and `openapi.json` to `build/generated/openapi`.

## Verification

Java 21 and a working Docker/Testcontainers environment are required, including for
the existing jOOQ generation. Tests never use the configured development database.

```shell
./gradlew detekt test integrationTest assemble lintDependencyDeclarations
./gradlew generateJooq --rerun-tasks
```

Test reports are under `build/reports/tests/test` and `build/reports/tests/integrationTest`.
The integration suite builds the packaged application and tests recovery by stopping
and restarting separate JVMs with retained, isolated PostgreSQL/Redis containers.
Its process logs are in `build/process-recovery.log`. The scheduler is disabled in
the regular test profile; tests invoke processors explicitly instead of racing it.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

## Database migrations and jOOQ generation

### Create the database and roles

Provision the database and roles separately from Flyway, using the standalone
administrator bootstrap prepared for the installation. It is not included in the
application migrations. The `plugin_manager_migration` role owns the database and
creates schema objects; `plugin_manager_app` has CONNECT, schema USAGE and data
access, but no schema CREATE permission. Default privileges of the migration role
must grant SELECT/INSERT/UPDATE/DELETE on future tables and USAGE/SELECT on sequences
to the application role. Application tables are created by Flyway at startup.

The local-development defaults in `application.properties` are:

| Setting | Value |
| --- | --- |
| Database | `plugin_manager` |
| Application username | `plugin_manager_app` |
| Application password | `plugin_manager_app_local_2026` |
| Migration username | `plugin_manager_migration` |
| Migration password | `plugin_manager_migration_local_2026` |

Use a dedicated Plugin Manager database. For an existing installation, verify role
ownership and privileges before starting the application. For non-local environments, provision secure passwords
and configure `DB_PASSWORD` and `DB_MIGRATION_PASSWORD` accordingly; do not use the
development passwords.

### Apply migrations and generate models

Put Flyway SQL migrations in `src/main/resources/db/migration` using names such as
`V1__create_plugins.sql`. Quarkus applies them automatically on application startup.

Generate the jOOQ model with:

```shell script
./gradlew generateJooq
```

The task starts an ephemeral PostgreSQL Testcontainer, applies all Flyway migrations,
and writes the generated Java model to `build/generated/sources/jooq/main/java`. The output
directory is intentionally excluded from Git and is also generated automatically before
the project's Java or Kotlin compilation tasks.

Runtime database access uses jOOQ over a pooled R2DBC connection. Configure it with the
following environment variables: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD`,
`DB_POOL_INITIAL_SIZE`, `DB_POOL_MAX_SIZE`, `DB_POOL_MAX_IDLE_TIME`,
`DB_POOL_MAX_ACQUIRE_TIME`, `DB_POOL_MAX_CREATE_CONNECTION_TIME`, and
`DB_MIGRATION_POOL_MAX_SIZE`. Duration values use ISO-8601 notation, for example `PT10S`
or `PT30M`. The `plugin_manager_app` role and R2DBC pool are used for application queries;
the separate `plugin_manager_migration` role and small JDBC pool are used only by Flyway.

Use `JooqTransaction` for suspend transactions and execute all statements through the
transaction-scoped `DSLContext` passed to the block:

```kotlin
transaction.transaction { dsl ->
    dsl.insertInto(/* table */)
        .set(/* field */, /* value */)
        .awaitSingle()
}
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.

## Creating a native executable

Native compilation requires Docker and a running Docker daemon. The build first generates jOOQ and Protobuf sources, then produces the native runner:

```shell script
./gradlew nativeTest \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.package.jar.enabled=false
```

The command creates `build/plugin-manage-service-1.0.0-SNAPSHOT-runner` and runs the native-image liveness test against that executable.

To build the executable without running native tests, use:

```shell script
./gradlew quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.package.jar.enabled=false
```

The Dockerfile is runtime-only and packages the prebuilt native runner; it does not run Gradle or Testcontainers during `docker build`:

```shell script
docker build -t plugin-manage-service:native .
docker run --rm -p 8080:8080 -p 9000:9000 plugin-manage-service:native
```

The CI pipeline runs the `native-test` stage after JVM integration tests. It builds the native image in a container and executes `nativeTest`.

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Related Guides

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin
- Quartz ([guide](https://quarkus.io/guides/quartz)): Schedule clustered tasks with Quartz
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
