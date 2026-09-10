package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Readiness for editor AI features.
 *
 * The gateway owns provider-specific HTTP transport, while [PluginContext.llmProvider]
 * is the source of truth for the provider, secret-backed credential, and default model.
 */
internal fun editorAiReadiness(context: PluginContext): AiReadiness {
    return AiAvailability.check(context)
}

/**
 * Wait briefly for the asynchronously loaded gateway and provider registry.
 *
 * Secret Manager warms its credential snapshot after register() returns, and plugin
 * registration order is asynchronous. A one-shot readiness check during the first
 * keystroke therefore observes a false "no provider" and drops the request forever.
 * The gateway's active model is authoritative because it also represents a selected
 * local CLI engine, for which [PluginContext.llmProvider] is correctly null.
 */
internal suspend fun awaitEditorAiGateway(
    context: PluginContext,
    timeoutMs: Long = 5_000,
    pollMs: Long = 100,
): AiGatewayAPI? =
    withTimeoutOrNull(timeoutMs) {
        while (true) {
            val gateway = runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull()
            val activeModel = runCatching { gateway?.activeModel() }.getOrNull()
            if (gateway != null && activeModel != null) {
                return@withTimeoutOrNull gateway
            }
            delay(pollMs)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
