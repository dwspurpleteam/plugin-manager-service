package ru.itmo.pluginmanager.worker

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.config.RuntimePolicyValidator
import ru.itmo.pluginmanager.support.policy

class PolicyContractTest {
    /**
     * Тест проверяет ранний отказ при несовместимых настройках worker.
     * Ожидаемый результат: короткий lease, нулевой лимит и недостаток задержек отвергаются.
     */
    @Test
    fun `unsafe worker policy is rejected before startup`() {
        for (overrides in listOf(
            mapOf("plugin-manager.worker.lease-duration" to "PT1S"),
            mapOf("plugin-manager.health.concurrency" to "0"),
            mapOf("plugin-manager.activation.attempts" to "4"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                RuntimePolicyValidator(policy(overrides))
            }
        }
    }
}
