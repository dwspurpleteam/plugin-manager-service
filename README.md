# plugin-manage-service

Plugin Manager — управляющий контур активаций плагинов в звонках. Он обнаруживает
и кэширует манифесты, предоставляет маршруты сигналов и требования к входным
медиа, отслеживает доступность и хранит историю состояний. Он не пересылает
сигналы или медиаданные.

Передача контекста активации и доставка в Plugin Backend описаны в
[docs/activation-context.md](docs/activation-context.md). Сгенерированные схемы
API доступны в форматах [OpenAPI YAML](docs/openapi/openapi.yaml) и
[OpenAPI JSON](docs/openapi/openapi.json).

Модульные тесты находятся в `src/test/kotlin` (`./gradlew test`). Интеграционные
тесты JVM находятся в `src/integrationTest/kotlin` (`./gradlew integrationTest`)
и используют изолированную инфраструктуру. Запустить оба набора можно командой
`./gradlew test integrationTest`.

## Конфигурация и первая активация

Предоставьте PostgreSQL и Redis и настройте хотя бы один реестр:

```properties
plugin-manager.registries.example-registry.url=https://registry.example.com
```

Для доступа к базе данных используются описанные ниже переменные `DB_*`. Redis использует
`REDIS_URL` (по умолчанию `redis://localhost:6379`). URL реестров и все политики
`plugin-manager.*` можно задать в `application.properties`, через системные свойства
Java перед `-jar` или через соответствующие переменные окружения Quarkus/SmallRye.
Значения по умолчанию для тайм-аутов, параллелизма, повторных попыток, TTL и хранения
перечислены в документе контракта. При запуске отклоняются некорректные ограничения
и несовместимые настройки lease/deadline.

Профиль `local` подключается к настроенному источнику PostgreSQL по адресу
`localhost:5432/plugin_manager` с выделенными ролями приложения и миграции:

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
  -d '{"callId":"call-10","pluginId":"example-plugin","requestedBy":"user-12","idempotencyKey":"activate-1","initialContext":{"callId":"call-10","participants":[],"snapshot":{"capturedAt":"2026-11-08T12:00:00Z","source":"call-backend","consistency":"point-in-time"}}}'
```

Регистрация возвращает 202 и идентификатор активации, не дожидаясь ответа реестра
или healthcheck. Опросите `/v1/activations/{activationId}`; только состояние ACTIVE
позволяет читать
`/signal-route` и `/media-types`. Для точной версии добавьте `pluginVersion`; если
версия не указана, выбирается текущая latest первого подходящего реестра и
фиксируется для этой активации. Повторно используйте ключ только для идентичного
исходного запроса.

`initialContext` необязателен: активация может быть создана без начального снимка.
Если он передан, это доверенный и сохраняемый снимок конкретного звонка, переданный
Call Backend; его `callId` должен совпадать с запросом. Если поле отсутствует,
it is absent, Plugin Manager sends `initialContext: null` to Plugin Backend. After a
successful healthcheck, Plugin Manager POSTs schema version `1.0` to the selected
manifest's `backend.events.event-url`: `activationId`, `pluginId`, `pluginVersion`,
`registryId`, `callId` and `initialContext`. The context is deliberately platform
neutral: it contains no room/track SIDs, API credentials or browser-originated data.

Документ OpenAPI доступен по адресу `/q/openapi`, а Swagger UI — по адресу
`/swagger-ui` in development and packaged applications. Each build also writes
`openapi.yaml` and `openapi.json` to `build/generated/openapi`.

## Проверка

Требуются Java 21 и работающая среда Docker/Testcontainers, в том числе для
the existing jOOQ generation. Tests never use the configured development database.

```shell
./gradlew detekt test integrationTest assemble lintDependencyDeclarations
./gradlew generateJooq --rerun-tasks
```

Отчёты о тестах находятся в `build/reports/tests/test` и `build/reports/tests/integrationTest`.
The integration suite builds the packaged application and tests recovery by stopping
and restarting separate JVMs with retained, isolated PostgreSQL/Redis containers.
Its process logs are in `build/process-recovery.log`. The scheduler is disabled in
the regular test profile; tests invoke processors explicitly instead of racing it.

Проект использует Quarkus — Supersonic Subatomic Java Framework.

Подробнее о Quarkus см. на сайте: <https://quarkus.io/>.

## Запуск приложения в режиме разработки

Запустить приложение в режиме разработки с поддержкой live coding можно командой:

```shell script
./gradlew quarkusDev
```

## Миграции базы данных и генерация jOOQ

### Создание базы данных и ролей

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

### Применение миграций и генерация моделей

Put Flyway SQL migrations in `src/main/resources/db/migration` using names such as
`V1__create_plugins.sql`. Quarkus applies them automatically on application startup.

Сгенерируйте модель jOOQ командой:

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

## Сборка и запуск приложения

Упаковать приложение можно командой:

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

### Building the JVM container image

Java 21 and a running Docker daemon are required. The Quarkus `imageBuild` task
packages the application and builds the image using
`src/main/docker/Dockerfile.jvm`:

```shell script
./gradlew imageBuild \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.builder=docker \
  -Dquarkus.container-image.group=local \
  -Dquarkus.container-image.name=plugin-manage-service \
  -Dquarkus.container-image.tag=jvm
```

The resulting local image is `local/plugin-manage-service:jvm`.

## Создание native-исполняемого файла

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

## Связанные руководства

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin
- Quartz ([guide](https://quarkus.io/guides/quartz)): Schedule clustered tasks with Quartz
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
