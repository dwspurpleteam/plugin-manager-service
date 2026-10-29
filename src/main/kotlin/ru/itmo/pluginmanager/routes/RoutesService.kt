package ru.itmo.pluginmanager.routes

import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionResponse
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionResponse

interface RoutesService {
    suspend fun getPermissionsForEvent(request: CheckEventPermissionRequest): CheckEventPermissionResponse
    suspend fun getPermissionsForMedia(request: CheckMediaPermissionRequest): CheckMediaPermissionResponse
}
