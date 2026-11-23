package ru.itmo.pluginmanager.persistence

import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Duration
import java.time.Instant

/** Сохраняемые задания с захватом SKIP LOCKED и защитой результатов токеном и сроком lease. */
interface WorkStore {
    /** Захватывает готовые или просроченные задания; попытка учитывается до выполнения внешних запросов. */
    suspend fun claim(kind: WorkKind, limit: Int, leaseDuration: Duration): List<WorkLease>

    /** Проверяет, что lease активации ещё действует перед отправкой команды. */
    suspend fun verifyActivationLease(lease: WorkLease)

    /** Освобождает lease и сохраняет следующую попытку доставки команды, не сбрасывая её счётчик. */
    suspend fun retryDelivery(lease: WorkLease, nextRunAt: Instant, reason: ErrorCode)

    /** Фиксирует проверку и планирует следующую; при пороге ошибок атомарно пишет FAILED и историю. */
    suspend fun recordHealthcheck(lease: WorkLease, healthy: Boolean, nextRunAt: Instant, failureThreshold: Int)
}
