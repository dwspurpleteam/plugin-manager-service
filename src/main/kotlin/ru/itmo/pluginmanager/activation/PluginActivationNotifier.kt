package ru.itmo.pluginmanager.activation

import ru.itmo.pluginmanager.domain.PluginActivationMessage
import ru.itmo.pluginmanager.domain.PluginDeactivationMessage

/** Отправляет Plugin Backend команды жизненного цикла активации. */
interface PluginActivationNotifier {
    /** Доставляет сообщение на заданный в манифесте endpoint либо сообщает об ошибке доставки. */
    suspend fun notify(endpoint: String, message: PluginActivationMessage)

    /** Доставляет команду завершения активации в звонке либо сообщает об ошибке доставки. */
    suspend fun deactivate(endpoint: String, message: PluginDeactivationMessage)
}
