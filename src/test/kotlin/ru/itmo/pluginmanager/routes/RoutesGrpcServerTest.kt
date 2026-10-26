package ru.itmo.pluginmanager.routes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionResponse
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionResponse

class RoutesGrpcServerTest {
    /**
     * Тест проверяет регистрацию RPC-методов из контракта RoutesService.
     * Ожидаемый результат: сервер содержит методы CheckEventPermission и CheckMediaPermission.
     */
    @Test
    fun `binds contract RPC methods`() {
        val methods = RoutesGrpcServer(object : RoutesService {
            override suspend fun getPermissionsForEvent(request: CheckEventPermissionRequest) =
                CheckEventPermissionResponse.getDefaultInstance()

            override suspend fun getPermissionsForMedia(request: CheckMediaPermissionRequest) =
                CheckMediaPermissionResponse.getDefaultInstance()
        })
            .bindService()
            .methods
            .map { it.methodDescriptor.fullMethodName }
            .toSet()

        assertEquals(
            setOf(
                "routes.v1.RoutesService/CheckEventPermission",
                "routes.v1.RoutesService/CheckMediaPermission",
            ),
            methods,
        )
    }
}
