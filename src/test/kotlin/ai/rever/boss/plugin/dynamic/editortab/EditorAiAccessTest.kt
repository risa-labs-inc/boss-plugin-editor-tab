package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.LlmProvider
import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EditorAiAccessTest {
    @Test
    fun `waits for the active model instead of dropping the first request`() {
        runBlocking {
            var reads = 0
            val gateway = gateway { if (++reads < 3) null else configuredModel() }

            val resolved = awaitEditorAiGateway(context(gateway, null), timeoutMs = 1_000, pollMs = 1)

            assertSame(gateway, resolved)
            assertNotNull(gateway.activeModel())
        }
    }

    @Test
    fun `times out when no shared provider becomes available`() {
        runBlocking {
            val resolved = awaitEditorAiGateway(context(gateway { null }, null), timeoutMs = 20, pollMs = 1)
            assertNull(resolved)
        }
    }

    private fun configuredModel() =
        AiModelInfo(
            providerId = "OLLAMA",
            providerName = "Ollama",
            modelId = "gemma4:latest",
        )

    private fun gateway(activeModel: () -> AiModelInfo?): AiGatewayAPI =
        Proxy.newProxyInstance(
            AiGatewayAPI::class.java.classLoader,
            arrayOf(AiGatewayAPI::class.java),
        ) { _, method, _ ->
            if (method.name == "activeModel") activeModel() else defaultValue(method.returnType)
        } as AiGatewayAPI

    private fun context(gateway: AiGatewayAPI, provider: LlmProvider?): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginAPI" -> gateway
                "getLlmProvider" -> provider
                else -> defaultValue(method.returnType)
            }
        } as PluginContext

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
}
