package ru.itmo.pluginmanager.persistence

import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.domain.RegistrationResult
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Instant
import java.util.UUID

/** Транзакционное хранилище; сетевые вызовы не выполняются внутри его транзакций. */
interface ActivationStore {
    /** Одной транзакцией создаёт активацию, начальную историю и задание с защитой уникальным ключом. */
    suspend fun register(request: ActivationRequest): RegistrationResult

    /** Читает активацию либо возвращает null. */
    suspend fun find(activationId: UUID): Activation?

    /** Читает сохранённый маршрут независимо от состояния; проверка доступности относится к сервису. */
    suspend fun route(activationId: UUID): ActivationRoute?

    /** Фиксирует первый выбор версии под действующим lease; последующая замена запрещена. */
    suspend fun pinSelection(lease: WorkLease, selection: PluginSelection)

    /** Сохраняет производные сведения только для уже зафиксированного выбора под действующим lease. */
    suspend fun saveRoute(lease: WorkLease, route: ActivationRoute)

    /** Атомарно проверяет маршрут, переводит в ACTIVE, пишет историю и назначает healthcheck. */
    suspend fun activate(lease: WorkLease, nextHealthcheckAt: Instant)

    /** Атомарно переводит в FAILED, пишет историю и удаляет задание, не запуская автоматическое восстановление. */
    suspend fun fail(lease: WorkLease, reason: ErrorCode)
}
