# plugin-manage-service

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

## Database migrations and jOOQ generation

Put Flyway SQL migrations in `src/main/resources/db/migration` using names such as
`V1__create_plugins.sql`. Quarkus applies them automatically on application startup.

Generate the jOOQ model with:

```shell script
./gradlew generateJooq
```

The task starts an ephemeral PostgreSQL Testcontainer, applies all Flyway migrations,
and writes the generated Java model to `src/quarkus-generated-sources/java`. The output
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

You can create a native executable using:

```shell script
./gradlew build -Dquarkus.native.enabled=true
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/plugin-manage-service-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Related Guides

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin
- Quartz ([guide](https://quarkus.io/guides/quartz)): Schedule clustered tasks with Quartz
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
