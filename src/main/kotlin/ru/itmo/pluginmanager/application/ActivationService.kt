package ru.itmo.pluginmanager.application

import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.RegistrationResult
import java.util.UUID

/** Сценарии регистрации и чтения активаций; маршруты и медиа доступны только в ACTIVE. */
interface ActivationService {
    /** Атомарно регистрирует активацию и задание либо возвращает совпадающий идемпотентный результат. */
    suspend fun register(request: ActivationRequest): RegistrationResult

    /** Возвращает состояние; неизвестный идентификатор приводит к ACTIVATION_NOT_FOUND. */
    suspend fun get(activationId: UUID): Activation

    /** Возвращает маршрут только готовой активной активации. */
    suspend fun signalRoute(activationId: UUID): ActivationRoute

    /** Возвращает входные типы медиа плагина только для активной активации. */
    suspend fun mediaTypes(activationId: UUID): Set<MediaType>
}
