# Контекст активации и доставка в Plugin Backend

В этом документе описаны доверенный снимок звонка, принимаемый Plugin Manager,
и сообщение, отправляемое выбранному Plugin Backend. Область активации всегда
ограничена одним звонком; `callId` — идентификатор корреляции звонка/комнаты.

## Источник и граница доверия

Call Backend является источником истины для снимка. Браузер не должен напрямую
отправлять в Plugin Manager роли участников, состояние медиа, учётные данные,
room/track SID или неограниченный список участников. Call Backend аутентифицирует
вызывающего, считывает состояние платформы, применяет список разрешённых данных и
передаёт полученный снимок в `initialContext`.

Plugin Manager проверяет только инвариант уровня контракта
`initialContext.callId == request.callId`. Он не объявляет данные браузера
доверенным источником и не добавляет в универсальный контракт идентификаторы,
специфичные для OpenVidu или LiveKit.

## Запрос регистрации

`POST /v1/activations` принимает необязательное поле `initialContext`. Если поле
не передано, в сообщении для backend используется `initialContext: null`.

```json
{
  "callId": "room-10",
  "pluginId": "example-plugin",
  "requestedBy": "call-backend-user-7",
  "idempotencyKey": "activate-room-10-example-1",
  "initialContext": {
    "callId": "room-10",
    "roomName": "Team meeting",
    "startDate": 1761472800000,
    "endDate": null,
    "participantCount": 2,
    "recordingActive": false,
    "maxParticipants": 10,
    "participants": [
      {
        "participantIdentity": "user-7",
        "participantName": "Ivan",
        "metadata": "{\"language\":\"en\"}",
        "role": "speaker",
        "joinDate": 1761472860000,
        "audioActive": true,
        "videoActive": true,
        "screenShareActive": false
      }
    ],
    "snapshot": {
      "capturedAt": "2026-11-08T12:00:00Z",
      "source": "call-backend",
      "consistency": "point-in-time"
    }
  }
}
```

`startDate`, `endDate` и `joinDate` участника — миллисекунды эпохи Unix.
`metadata` — непрозрачная строка, разрешённая списком; Plugin Manager её не
интерпретирует. `snapshot.source` идентифицирует доверенного производителя, а не
клиент браузера.

`snapshot.consistency` принимает только следующие значения:

- `point-in-time` — все данные относятся к одному зафиксированному моменту времени;
- `eventual` — данные согласуются с задержкой и могли быть получены из разных
  актуальных на момент чтения источников;
- `partial` — снимок намеренно содержит только часть доступного состояния.

Источник снимка (`Call Backend`) обязан выбрать значение, соответствующее способу
его формирования. Plugin Manager не повышает уровень гарантии и не преобразует
один тип согласованности в другой.

Контекст сериализуется в `plugin_activation.initial_context` (JSONB) в той же
транзакции, что и активация с её рабочим элементом `ACTIVATE`. Поэтому повторная
попытка, идемпотентный запрос или восстановление worker читают исходный снимок.
Запрос с повторным использованием той же пары `(callId, idempotencyKey)` должен
содержать идентичный контекст; его изменение возвращает `IDEMPOTENCY_CONFLICT` и
никогда не перезаписывает сохранённое значение.

## Сообщение для backend

После того как worker определяет и фиксирует конкретную версию плагина, выполняет
healthcheck и до перевода активации в `ACTIVE` он отправляет один HTTP `POST` на
`backend.events.event-url` выбранного манифеста.

```json
{
  "schemaVersion": "1.0",
  "activationId": "2e0d4e0e-5c8b-4f4e-a0b2-5c2c4e2d1f10",
  "pluginId": "example-plugin",
  "pluginVersion": "1.0.0",
  "registryId": "example-registry",
  "callId": "room-10",
  "initialContext": { "...": "same persisted snapshot or null" }
}
```

Сообщение содержит неизменяемый выбор реестра, сделанный для этой активации, что
не позволяет последующей версии `latest` изменить контракт доставки. Ответ не-2xx
или ошибка транспорта обрабатываются существующей политикой повторных попыток
активации; контекст не восстанавливается из браузера и не обновляется неявно.
Plugin Backend может обслуживать несколько звонков и должен изолировать их как
минимум по `activationId` и `callId`.

## Связанные контракты

- [OpenAPI YAML](openapi/openapi.yaml)
- [OpenAPI JSON](openapi/openapi.json)
