package ru.itmo.pluginmanager.worker

import java.time.Instant
import java.util.UUID

enum class WorkKind {
    ACTIVATE,
    HEALTHCHECK,
    DEACTIVATE,
}

data class WorkLease(
    val activationId: UUID,
    val token: UUID,
    val kind: WorkKind,
    val expiresAt: Instant,
    val attemptCount: Int,
    val consecutiveHealthFailures: Int,
    val requestedBy: String? = null,
)
