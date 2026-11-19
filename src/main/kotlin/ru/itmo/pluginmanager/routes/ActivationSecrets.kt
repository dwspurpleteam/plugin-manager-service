package ru.itmo.pluginmanager.routes

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MINIMUM_KEY_BYTES = 32

@ApplicationScoped
class ActivationSecrets(
    @ConfigProperty(
        name = "plugin-manager.activation-secret-key",
        defaultValue = "disabled",
    ) key: String,
) {
    private val keyBytes = key.toByteArray(StandardCharsets.UTF_8)

    fun issue(callId: String, activationId: String): String {
        if (keyBytes.size < MINIMUM_KEY_BYTES) {
            throw PluginManagerException(ErrorCode.INTERNAL_ERROR, "Activation secret key is not configured")
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val message = "$activationId\u0000$callId".toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
    }

    fun matches(callId: String, activationId: String, secret: String): Boolean {
        if (keyBytes.size < MINIMUM_KEY_BYTES || listOf(secret, callId, activationId).any { it.isBlank() }) return false
        val expected = issue(callId, activationId).toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected, secret.toByteArray(StandardCharsets.UTF_8))
    }
}
