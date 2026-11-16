package ru.itmo.pluginmanager.support

import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.domain.RegistrationResult
import ru.itmo.pluginmanager.persistence.ActivationStore
import ru.itmo.pluginmanager.persistence.WorkStore
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MemoryStores :
    ActivationStore,
    WorkStore {
    var activation = Activation(
        UUID.randomUUID(),
        ActivationRequest("call", "plugin", "user", "key"),
        null,
        ActivationState.ACTIVATING,
        1,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
    )
    var savedRoute: ActivationRoute? = null
    var retried = false
    var checked: Boolean? = null
    val lease get() = WorkLease(activation.activationId, UUID.randomUUID(), WorkKind.ACTIVATE, Instant.MAX, 1, 0)

    override suspend fun register(request: ActivationRequest) = RegistrationResult(activation.activationId, true)
    override suspend fun find(activationId: UUID) = activation.takeIf { it.activationId == activationId }
    override suspend fun findActiveByCall(callId: String) = listOf(activation).filter {
        it.request.callId == callId && it.state == ActivationState.ACTIVE
    }
    override suspend fun route(activationId: UUID) = savedRoute
    override suspend fun pinSelection(lease: WorkLease, selection: PluginSelection) {
        activation = activation.copy(selection = selection)
    }
    override suspend fun saveRoute(lease: WorkLease, route: ActivationRoute) {
        savedRoute = route
    }
    override suspend fun activate(lease: WorkLease, nextHealthcheckAt: Instant) {
        checkNotNull(savedRoute)
        activation = activation.copy(state = ActivationState.ACTIVE)
    }
    override suspend fun fail(lease: WorkLease, reason: ErrorCode) {
        activation = activation.copy(state = ActivationState.FAILED, failureCode = reason)
    }
    override suspend fun claim(kind: WorkKind, limit: Int, leaseDuration: Duration) = emptyList<WorkLease>()
    override suspend fun retryActivation(lease: WorkLease, nextRunAt: Instant, reason: ErrorCode) {
        retried = true
    }
    override suspend fun recordHealthcheck(
        lease: WorkLease,
        healthy: Boolean,
        nextRunAt: Instant,
        failureThreshold: Int,
    ) {
        checked = healthy
    }
}
