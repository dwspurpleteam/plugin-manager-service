package ru.itmo.pluginmanager.worker

/** Обработчик одного вида фоновой работы под действующим lease. */
interface WorkerProcessor {
    /** Вид работы, которую обрабатывает эта реализация. */
    val kind: WorkKind

    /** Выполняет одну захваченную задачу. */
    suspend fun process(lease: WorkLease)
}
