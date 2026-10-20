package ru.itmo.pluginmanager.domain

import java.net.URI

private const val MAX_IDENTIFIER_LENGTH = 255

internal fun validateIdentifier(value: String) {
    if (value.isBlank() || value.length > MAX_IDENTIFIER_LENGTH || value.any { it.isISOControl() }) {
        throw PluginManagerException(ErrorCode.INVALID_REQUEST, "Invalid identifier")
    }
}

internal fun validateHttpUrl(value: String, code: ErrorCode = ErrorCode.INVALID_MANIFEST) {
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw PluginManagerException(code, "Invalid HTTP URL")
    val validEndpoint = uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    if (!validEndpoint || uri.userInfo != null || uri.fragment != null) {
        throw PluginManagerException(code, "Invalid HTTP URL")
    }
}

internal fun validateManifest(selection: PluginSelection, manifest: PluginManifest) {
    if (manifest.pluginId != selection.pluginId || manifest.pluginVersion != selection.pluginVersion ||
        manifest.manifestVersion != "0.1"
    ) {
        throw PluginManagerException(ErrorCode.INVALID_MANIFEST, "Manifest identity or format mismatch")
    }
    validateHttpUrl(manifest.backend.events.eventUrl)
    validateHttpUrl(manifest.backend.healthUrl)
}
