package ru.itmo.pluginmanager.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.jboss.logging.Logger
import org.jooq.DSLContext
import org.jooq.impl.DSL.field
import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationContext
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_ROUTE
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_STATE_HISTORY
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_WORK
import ru.itmo.pluginmanager.jooq.generated.Tables.PLUGIN_ACTIVATION
import ru.itmo.pluginmanager.jooq.generated.tables.records.PluginActivationRecord
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationState as DatabaseActivationState

private val logger = Logger.getLogger("ru.itmo.pluginmanager.persistence.ActivationState")

internal fun storageError(code: ErrorCode = ErrorCode.INTERNAL_ERROR): Nothing =
    throw PluginManagerException(code, "Activation storage operation rejected")

internal suspend fun DSLContext.lockActivation(lease: WorkLease): PluginActivationRecord {
    val work = select(ACTIVATION_WORK.ACTIVATION_ID).from(ACTIVATION_WORK)
        .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId))
        .and(ACTIVATION_WORK.LEASE_TOKEN.eq(lease.token))
        .and(ACTIVATION_WORK.KIND.cast(String::class.java).eq(lease.kind.name))
        .and(ACTIVATION_WORK.LEASE_UNTIL.gt(org.jooq.impl.DSL.field("clock_timestamp()", OffsetDateTime::class.java)))
        .forUpdate().awaitFirstOrNull() ?: storageError(ErrorCode.LEASE_LOST)
    val activation = selectFrom(PLUGIN_ACTIVATION).where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(work.value1()))
        .forUpdate().awaitFirstOrNull() ?: storageError()
    if (activation.state != lease.kind.expectedState) {
        storageError(ErrorCode.LEASE_LOST)
    }
    return activation
}

internal val WorkKind.expectedState: DatabaseActivationState
    get() = when (this) {
        WorkKind.ACTIVATE -> DatabaseActivationState.ACTIVATING
        WorkKind.HEALTHCHECK -> DatabaseActivationState.ACTIVE
        WorkKind.DEACTIVATE -> DatabaseActivationState.STOPPING
    }

internal suspend fun DSLContext.changeState(
    record: PluginActivationRecord,
    state: ActivationState,
    reason: ErrorCode?,
) {
    val changed = OffsetDateTime.now(ZoneOffset.UTC)
    val revision = record.stateRevision + 1
    val databaseState = DatabaseActivationState.valueOf(state.name)
    update(PLUGIN_ACTIVATION).set(PLUGIN_ACTIVATION.STATE, databaseState)
        .set(PLUGIN_ACTIVATION.STATE_REVISION, revision)
        .set(PLUGIN_ACTIVATION.STATE_CHANGED_AT, changed)
        .set(PLUGIN_ACTIVATION.FAILURE_CODE, reason?.name)
        .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(record.activationId)).awaitSingle()
    insertInto(ACTIVATION_STATE_HISTORY)
        .set(ACTIVATION_STATE_HISTORY.HISTORY_ID, UUID.randomUUID())
        .set(ACTIVATION_STATE_HISTORY.ACTIVATION_ID, record.activationId)
        .set(ACTIVATION_STATE_HISTORY.REVISION, revision)
        .set(ACTIVATION_STATE_HISTORY.FROM_STATE, record.state)
        .set(ACTIVATION_STATE_HISTORY.TO_STATE, databaseState)
        .set(ACTIVATION_STATE_HISTORY.CHANGED_AT, changed)
        .set(ACTIVATION_STATE_HISTORY.REASON_CODE, reason?.name).awaitSingle()
    if (state == ActivationState.FAILED) {
        logger.errorf(
            "Activation transitioned to FAILED: activationId=%s, failureCode=%s",
            record.activationId,
            reason,
        )
    }
}

internal fun PluginActivationRecord.toDomain(mapper: ObjectMapper): Activation = Activation(
    activationId,
    ActivationRequest(
        callId,
        pluginId,
        requestedBy,
        idempotencyKey,
        requestedVersion,
        requestedRegistryId,
        initialContext?.data()?.let { mapper.readValue(it, ActivationContext::class.java) },
    ),
    resolvedVersion?.let { PluginSelection(pluginId, it, resolvedRegistryId) },
    ActivationState.valueOf(state.name),
    stateRevision,
    createdAt.toInstant(),
    stateChangedAt.toInstant(),
    failureCode?.let(ErrorCode::valueOf),
)

internal suspend fun DSLContext.readRoute(id: UUID): ActivationRoute? = select(
    ACTIVATION_ROUTE.asterisk(),
    field("allowed_actions", Array<String>::class.java),
    field("include_participants", Boolean::class.java),
).from(ACTIVATION_ROUTE).where(ACTIVATION_ROUTE.ACTIVATION_ID.eq(id)).awaitFirstOrNull()?.let {
    ActivationRoute(
        checkNotNull(it.get(ACTIVATION_ROUTE.ROUTE_ID)),
        id,
        checkNotNull(it.get(ACTIVATION_ROUTE.SIGNAL_DST)),
        it.get(ACTIVATION_ROUTE.ALLOWED_EVENT_TYPES)?.toSet(),
        it.get(ACTIVATION_ROUTE.ALLOWED_EVENT_GROUPS)?.toSet(),
        checkNotNull(it.get(ACTIVATION_ROUTE.HEALTHCHECK_URL)),
        checkNotNull(it.get(ACTIVATION_ROUTE.MEDIA_TYPES))
            .map { media -> MediaType.valueOf(media.name) }.toSet(),
        it.get(field("allowed_actions", Array<String>::class.java)).toSet(),
        it.get(field("include_participants", Boolean::class.java)) ?: false,
    )
}
