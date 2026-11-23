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
Plugin Manager отправляет в Plugin Backend `initialContext: null`. После успешной
проверки доступности Plugin Manager отправляет POST-запрос со схемой версии `1.0`
на `backend.control-url` выбранного манифеста; запрос содержит `activationId`,
`pluginId`, `pluginVersion`, `registryId`, `callId` и `initialContext`. Контекст
намеренно не зависит от платформы: он не содержит SID комнаты или медиатреков,
учётных данных API и данных, полученных из браузера.

Для деактивации вызовите `POST /v1/activations/{activationId}/deactivate` с телом
`{"requestedBy":"user-42"}`. Plugin Manager отправляет POST на `backend.control-url`
с полями `schemaVersion: "1.0"`, `operation: "deactivate"`, `activationId`,
`callId`, `pluginId`, `requestedBy` и `activationSecret`.

Plugin Backend должен завершить работу для указанной пары `callId` и `activationId`
и вернуть `2xx` после завершения. Повтор команды для этой пары должен возвращать
`2xx`, а запоздалая команда активации с тем же ID не должна запускать плагин снова.
REST-метод сохраняет задание `DEACTIVATE` и возвращает `202 Accepted` с `Location`
для чтения состояния. Воркер повторяет доставку с задержками активации и сохраняет
`STOPPED` после подтверждения. Пока задание выполняется, активация находится в
`STOPPING`, а в ответе на POST поля `success` и `deactivatedAt` равны `false` и `null`.
Исходный адрес управления сохраняется отдельно от маршрута и используется даже
после его очистки. Если маршрут ещё не был создан, воркер завершает деактивацию
локально. Для старых активаций с уже потерянным адресом API возвращает ошибку
доставки деактивации.

Документ OpenAPI доступен по адресу `/q/openapi`, а Swagger UI — по адресу
`/swagger-ui` в режиме разработки и в упакованном приложении. Каждая сборка также
записывает `openapi.yaml` и `openapi.json` в `build/generated/openapi`.

## Проверка

Требуются Java 21 и работающая среда Docker/Testcontainers, в том числе для
существующей генерации jOOQ. Тесты никогда не используют настроенную базу данных
для разработки.

```shell
./gradlew detekt test integrationTest assemble lintDependencyDeclarations
./gradlew generateJooq --rerun-tasks
```

Отчёты о тестах находятся в `build/reports/tests/test` и `build/reports/tests/integrationTest`.
Набор интеграционных тестов собирает упакованное приложение и проверяет восстановление,
останавливая и перезапуская отдельные JVM при сохранённых изолированных контейнерах
PostgreSQL/Redis. Логи процессов находятся в `build/process-recovery.log`. Планировщик
отключён в обычном тестовом профиле; тесты явно вызывают обработчики, не конкурируя с ним.

Проект использует Quarkus — Java-фреймворк Supersonic Subatomic.

Подробнее о Quarkus см. на сайте: <https://quarkus.io/>.

## Запуск приложения в режиме разработки

Запустить приложение в режиме разработки с поддержкой live coding можно командой:

```shell script
./gradlew quarkusDev
```

## Миграции базы данных и генерация jOOQ

### Создание базы данных и ролей

Подготовьте базу данных и роли отдельно от Flyway с помощью отдельного
администраторского bootstrap-скрипта, предназначенного для установки. Он не входит
в миграции приложения. Роль `plugin_manager_migration` владеет базой данных и
создаёт объекты схемы; `plugin_manager_app` имеет права CONNECT, USAGE для схемы и
доступ к данным, но не имеет права CREATE для схемы. Привилегии по умолчанию роли
миграций должны предоставлять роли приложения права SELECT/INSERT/UPDATE/DELETE на
будущие таблицы и USAGE/SELECT на последовательности. Таблицы приложения создаются
Flyway при запуске.

Значения по умолчанию для локальной разработки в `application.properties`:

| Параметр | Значение |
| --- | --- |
| Database | `plugin_manager` |
| Application username | `plugin_manager_app` |
| Application password | `plugin_manager_app_local_2026` |
| Migration username | `plugin_manager_migration` |
| Migration password | `plugin_manager_migration_local_2026` |

Используйте отдельную базу данных Plugin Manager. Для существующей установки перед
запуском приложения проверьте владельцев ролей и привилегии. В средах, отличных от
локальной, задайте безопасные пароли и настройте `DB_PASSWORD` и
`DB_MIGRATION_PASSWORD`; не используйте пароли для разработки.

### Применение миграций и генерация моделей

Размещайте SQL-миграции Flyway в `src/main/resources/db/migration`, используя имена
вида `V1__create_plugins.sql`. Quarkus автоматически применяет их при запуске приложения.

Сгенерируйте модель jOOQ командой:

```shell script
./gradlew generateJooq
```

Задача запускает временный PostgreSQL Testcontainer, применяет все миграции Flyway и
записывает сгенерированную Java-модель в `build/generated/sources/jooq/main/java`.
Каталог с результатами намеренно исключён из Git и также автоматически создаётся
перед задачами компиляции Java или Kotlin проекта.

Для доступа к базе данных во время работы используется jOOQ поверх пулированного
соединения R2DBC. Настройте его следующими переменными окружения: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD`,
`DB_POOL_INITIAL_SIZE`, `DB_POOL_MAX_SIZE`, `DB_POOL_MAX_IDLE_TIME`,
`DB_POOL_MAX_ACQUIRE_TIME`, `DB_POOL_MAX_CREATE_CONNECTION_TIME` и
`DB_MIGRATION_POOL_MAX_SIZE`. Значения длительности используют формат ISO-8601, например `PT10S`
или `PT30M`. Роль `plugin_manager_app` и пул R2DBC используются для запросов приложения;
отдельная роль `plugin_manager_migration` и небольшой пул JDBC используются только Flyway.

Используйте `JooqTransaction` для suspend-транзакций и выполняйте все запросы через
переданный в блок `DSLContext`, ограниченный областью транзакции:

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

Команда создаёт файл `quarkus-run.jar` в каталоге `build/quarkus-app/`.
Это не _über-jar_: зависимости копируются в каталог `build/quarkus-app/lib/`.

После этого приложение можно запустить командой `java -jar build/quarkus-app/quarkus-run.jar`.

Чтобы собрать _über-jar_, выполните следующую команду:

```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```

Приложение, упакованное как _über-jar_, можно запустить командой `java -jar build/*-runner.jar`.

### Сборка образа контейнера JVM

Требуются Java 21 и работающий демон Docker. Задача Quarkus `imageBuild` упаковывает
приложение и собирает образ с помощью
`src/main/docker/Dockerfile.jvm`:

```shell script
./gradlew imageBuild \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.builder=docker \
  -Dquarkus.container-image.group=local \
  -Dquarkus.container-image.name=plugin-manage-service \
  -Dquarkus.container-image.tag=jvm
```

Результирующий локальный образ — `local/plugin-manage-service:jvm`.

## Создание native-исполняемого файла

Для нативной компиляции требуются Docker и работающий демон Docker. Сначала сборка
генерирует исходный код jOOQ и Protobuf, затем создаёт native runner:

```shell script
./gradlew nativeTest \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.package.jar.enabled=false
```

Команда создаёт `build/plugin-manage-service-1.0.0-SNAPSHOT-runner` и запускает
проверку работоспособности native-image для этого исполняемого файла.

Чтобы собрать исполняемый файл без запуска native-тестов, используйте:

```shell script
./gradlew quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.package.jar.enabled=false
```

Dockerfile предназначен только для запуска и упаковывает предварительно собранный
native runner; Gradle и Testcontainers не запускаются во время `docker build`:

```shell script
docker build -t plugin-manage-service:native .
docker run --rm -p 8080:8080 -p 9000:9000 plugin-manage-service:native
```

CI pipeline запускает этап `native-test` после интеграционных тестов JVM. Он собирает
native image в контейнере и выполняет `nativeTest`.

Подробнее о сборке нативных исполняемых файлов см. в руководстве
<https://quarkus.io/guides/gradle-tooling>.

## Связанные руководства

- REST Jackson ([руководство](https://quarkus.io/guides/rest#json-serialisation)): поддержка сериализации Jackson для Quarkus REST. Это расширение несовместимо с расширением quarkus-resteasy и любыми расширениями, которые от него зависят
- Flyway ([руководство](https://quarkus.io/guides/flyway)): управление миграциями схемы базы данных
- Kotlin ([руководство](https://quarkus.io/guides/kotlin)): написание сервисов на Kotlin
- Quartz ([руководство](https://quarkus.io/guides/quartz)): планирование кластеризованных задач с Quartz
- JDBC Driver — PostgreSQL ([руководство](https://quarkus.io/guides/datasource)): подключение к базе данных PostgreSQL через JDBC
