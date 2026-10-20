package ru.itmo.pluginmanager.health

import ru.itmo.pluginmanager.worker.WorkLease

/** Периодическая проверка активного плагина без восстановления из FAILED. */
interface HealthcheckProcessor {
    /** Проверяет сохранённый health URL и атомарно учитывает результат под действующим lease. */
    suspend fun process(lease: WorkLease)
}
