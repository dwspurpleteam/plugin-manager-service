package ru.itmo.pluginmanager.persistence

import java.time.Instant

/** Очистка ограниченными пачками без удаления активаций и актуальной истории. */
interface MaintenanceStore {
    /** Удаляет старую историю, сохраняя запись текущей ревизии; параллельные запуски координируются БД. */
    suspend fun deleteHistoryBefore(cutoff: Instant, limit: Int): Int

    /** Удаляет маршруты только для STOPPED, FAILED, SUSPENDED под блокировкой соответствующей активации. */
    suspend fun deleteInactiveRoutes(limit: Int): Int
}
