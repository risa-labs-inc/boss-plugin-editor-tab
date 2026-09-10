package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.PluginContext

/**
 * Readiness for editor AI features.
 *
 * The gateway owns provider-specific HTTP transport, while [PluginContext.llmProvider]
 * is the source of truth for the provider, secret-backed credential, and default model.
 */
internal fun editorAiReadiness(context: PluginContext): AiReadiness {
    val gatewayReadiness = AiAvailability.check(context)
    if (gatewayReadiness != AiReadiness.READY) return gatewayReadiness

    val activeConfig = runCatching { context.llmProvider?.activeConfig() }.getOrNull()
    return if (activeConfig == null || activeConfig.modelId.isBlank()) {
        AiReadiness.NO_PROVIDER
    } else {
        AiReadiness.READY
    }
}
