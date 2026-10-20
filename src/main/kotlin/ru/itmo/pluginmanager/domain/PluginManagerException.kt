package ru.itmo.pluginmanager.domain

enum class ErrorCode {
    INVALID_REQUEST,
    UNKNOWN_REGISTRY,
    PLUGIN_NOT_FOUND,
    ACTIVATION_NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    ACTIVATION_NOT_READY,
    ACTIVATION_FAILED,
    ACTIVATION_NOT_ACTIVE,
    REGISTRY_UNAVAILABLE,
    REGISTRY_INVALID_RESPONSE,
    INVALID_MANIFEST,
    HEALTHCHECK_FAILED,
    ACTIVATION_ATTEMPTS_EXHAUSTED,
    LEASE_LOST,
    DATABASE_UNAVAILABLE,
    INTERNAL_ERROR,
}

class PluginManagerException(val code: ErrorCode, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
