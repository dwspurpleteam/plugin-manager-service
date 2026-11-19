INSERT INTO manifest_version (version, schema)
VALUES ('1.1', $schema$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Манифест плагина 1.1",
  "type": "object",
  "additionalProperties": false,
  "required": ["manifest-version", "plugin-id", "plugin-version", "name", "frontend", "backend"],
  "properties": {
    "manifest-version": { "const": "1.1" },
    "plugin-id": { "$ref": "#/$defs/nonBlankString" },
    "plugin-version": { "$ref": "#/$defs/nonBlankString" },
    "name": { "$ref": "#/$defs/nonBlankString" },
    "description": { "$ref": "#/$defs/nonBlankString" },
    "frontend": {
      "type": "object",
      "additionalProperties": false,
      "required": ["url-web"],
      "properties": {
        "url-web": { "$ref": "#/$defs/httpsUrl" },
        "sdk-version": { "$ref": "#/$defs/nonBlankString" }
      }
    },
    "backend": {
      "type": "object",
      "additionalProperties": false,
      "required": ["health-url", "control-url"],
      "properties": {
        "health-url": { "$ref": "#/$defs/publicHttpsUrl" },
        "control-url": { "$ref": "#/$defs/publicHttpsUrl" },
        "sdk-version": { "$ref": "#/$defs/nonBlankString" },
        "initial-state": {
          "type": "array",
          "uniqueItems": true,
          "default": [],
          "items": { "enum": ["participants"] }
        },
        "events": { "$ref": "#/$defs/events" },
        "actions": {
          "type": "array",
          "uniqueItems": true,
          "default": [],
          "items": { "$ref": "#/$defs/nonBlankString" }
        },
        "media": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "input": { "$ref": "#/$defs/mediaTypes" },
            "output": { "$ref": "#/$defs/mediaTypes" }
          }
        },
        "results": {
          "type": "object",
          "additionalProperties": false,
          "required": ["keys"],
          "properties": {
            "keys": { "$ref": "#/$defs/nonEmptyStringList" },
            "delivery-report-url": { "$ref": "#/$defs/publicHttpsUrl" }
          }
        }
      }
    }
  },
  "$defs": {
    "nonBlankString": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S"
    },
    "httpsUrl": {
      "type": "string",
      "format": "https-uri"
    },
    "publicHttpsUrl": {
      "type": "string",
      "format": "public-https-uri"
    },
    "nonEmptyStringList": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "$ref": "#/$defs/nonBlankString" }
    },
    "mediaTypes": {
      "type": "array",
      "uniqueItems": true,
      "default": [],
      "items": { "enum": ["AUDIO", "VIDEO", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"] }
    },
    "events": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "available-event-types": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": {
            "enum": [
              "participant.joined",
              "participant.left",
              "participant.connection_aborted",
              "participant.reconnecting",
              "participant.reconnected",
              "participant.kicked",
              "media.audio_muted",
              "media.audio_unmuted",
              "media.video_enabled",
              "media.video_disabled",
              "media.screen_share_started",
              "media.screen_share_stopped",
              "media.track_published",
              "media.track_unpublished",
              "interaction.chat_message_received",
              "call.ended"
            ]
          }
        },
        "available-event-groups": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": { "enum": ["participant", "media", "interaction", "call"] }
        }
      },
      "anyOf": [
        { "required": ["available-event-types"] },
        { "required": ["available-event-groups"] }
      ]
    }
  }
}
$schema$::jsonb)
ON CONFLICT (version) DO NOTHING;
