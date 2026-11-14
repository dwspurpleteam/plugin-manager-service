# Activation context and Plugin Backend delivery

This document describes the trusted call snapshot accepted by Plugin Manager and
the message sent to the selected Plugin Backend. The activation scope is always
per-call; `callId` is the call/room correlation identifier.

## Source and trust boundary

The Call Backend is the source of truth for the snapshot. A browser must not send
participant roles, media state, credentials, room/track SIDs, or an unrestricted
participant list directly to Plugin Manager. The Call Backend authenticates the
caller, reads the platform state, applies its allowlist, and sends the resulting
snapshot in `initialContext`.

Plugin Manager validates only the contract-level invariant
`initialContext.callId == request.callId`. It does not promote browser data to a
trusted source and does not add OpenVidu- or LiveKit-specific identifiers to the
universal contract.

## Registration request

`POST /v1/activations` accepts the optional `initialContext` field. Omitting the
field is backward compatible and results in `initialContext: null` in the backend
message.

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
        "externalId": "external-user-42",
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

`startDate`, `endDate`, and participant `joinDate` are epoch milliseconds.
`metadata` is an opaque, allowlisted string; it is not interpreted by Plugin
Manager. `snapshot.source` identifies the trusted producer, not a browser client.

The context is serialized into `plugin_activation.initial_context` (JSONB) in the
same transaction as the activation and its `ACTIVATE` work item. Therefore a retry,
an idempotent request, or worker recovery reads the original snapshot. A request
reusing the same `(callId, idempotencyKey)` must contain the identical context;
changing it returns `IDEMPOTENCY_CONFLICT` and never overwrites the stored value.

## Backend message

After the worker resolves and pins the concrete plugin version, performs the
healthcheck, and before it marks the activation `ACTIVE`, it sends one HTTP `POST`
to the selected manifest's `backend.events.event-url`.

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

The message carries the immutable registry selection made for this activation,
which prevents a later `latest` version from changing the delivery contract. A
non-2xx response or transport failure follows the existing activation retry policy;
the context is not reconstructed from the browser or refreshed implicitly. The
Plugin Backend can serve multiple calls and must isolate them using at least
`activationId` and `callId`.

## Related contracts

- [Plugin Manager contracts](plugin-manager-contracts.md)
- [OpenAPI YAML](openapi/openapi.yaml)
- [OpenAPI JSON](openapi/openapi.json)
