package ru.itmo.pluginmanager.activation

import ru.itmo.pluginmanager.worker.WorkLease

/** Выполнение одной сохранённой фоновой активации с ограниченными попытками. */
interface ActivationProcessor {
    /** Восстанавливает выбор версии, получает конфигурацию, сохраняет маршрут и выполняет healthcheck. */
    suspend fun process(lease: WorkLease)
}
