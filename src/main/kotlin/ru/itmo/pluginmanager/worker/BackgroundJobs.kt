package ru.itmo.pluginmanager.worker

/** Точки входа планировщика; PostgreSQL, а не память процесса, является источником заданий. */
interface BackgroundJobs {
    /** Запускает задачи в планировщике */
    suspend fun runTasks()

    /** Удаляет устаревшую историю и допустимые неактивные маршруты ограниченными пачками. */
    suspend fun cleanUp()
}
