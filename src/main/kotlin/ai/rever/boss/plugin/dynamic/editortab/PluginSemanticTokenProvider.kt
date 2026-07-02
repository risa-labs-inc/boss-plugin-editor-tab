package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.SemanticTokenProvider
import ai.rever.boss.plugin.api.SemanticElement
import ai.rever.boss.plugin.api.SemanticElementType
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bosseditor.psi.PSIBootstrap
import ai.rever.bosseditor.psi.PSIThreadBridge
import ai.rever.bosseditor.psi.SemanticCache
import ai.rever.bosseditor.psi.SemanticType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import ai.rever.bosseditor.psi.SemanticElement as EditorSemanticElement

private val logger = BossLogger.forComponent("PluginSemanticTokenProvider")

/**
 * Semantic highlighting backed by the BossEditor PSI stack bundled inside this
 * plugin's JAR. Ported from the host's SemanticTokenProviderImpl when BossEditor
 * moved off the host classpath — the plugin now owns the PSI infrastructure, so
 * the host can no longer provide this through PluginContext.
 */
internal class PluginSemanticTokenProvider : SemanticTokenProvider {

    override fun getSemanticElements(filePath: String): List<SemanticElement>? {
        // Only support Kotlin files
        if (!filePath.endsWith(".kt") && !filePath.endsWith(".kts")) {
            return null
        }

        val cachedElements = SemanticCache.get(filePath) ?: return null

        // Convert BossEditor elements to plugin API elements
        return cachedElements.map { element ->
            SemanticElement(
                startOffset = element.startOffset,
                endOffset = element.endOffset,
                type = mapSemanticType(element.type),
                name = element.name
            )
        }
    }

    override suspend fun analyzeFile(filePath: String, content: String) {
        // Only support Kotlin files
        if (!filePath.endsWith(".kt") && !filePath.endsWith(".kts")) {
            return
        }

        if (!PSIBootstrap.isInitialized) {
            logger.warn(LogCategory.EDITOR, "PSI not initialized, skipping semantic analysis")
            return
        }

        if (content.isEmpty()) {
            SemanticCache.clear(filePath)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val fileName = filePath.substringAfterLast('/')
                val elements = PSIThreadBridge.readAction {
                    val ktFile = PSIBootstrap.parseKotlinFile(fileName, content)
                    analyzeKotlinFile(ktFile)
                }
                SemanticCache.put(filePath, elements)
            } catch (e: Exception) {
                logger.error(LogCategory.EDITOR, "Semantic analysis error", error = e)
            }
        }
    }

    /**
     * Maps BossEditor SemanticType to plugin API SemanticElementType.
     */
    private fun mapSemanticType(type: SemanticType): SemanticElementType = when (type) {
        SemanticType.FUNCTION_CALL -> SemanticElementType.FUNCTION_CALL
        SemanticType.PROPERTY_ACCESS -> SemanticElementType.PROPERTY_ACCESS
        SemanticType.CLASS_REFERENCE -> SemanticElementType.CLASS_REFERENCE
        SemanticType.OBJECT_REFERENCE -> SemanticElementType.OBJECT_REFERENCE
        SemanticType.PARAMETER -> SemanticElementType.PARAMETER
        SemanticType.LOCAL_VARIABLE -> SemanticElementType.LOCAL_VARIABLE
        SemanticType.ANNOTATION -> SemanticElementType.ANNOTATION
        SemanticType.LABEL -> SemanticElementType.LABEL
        SemanticType.TYPE_PARAMETER -> SemanticElementType.TYPE_PARAMETER
    }

    /**
     * Check if an element is inside an import statement.
     */
    private fun isInsideImport(element: KtElement): Boolean {
        return element.getParentOfType<KtImportDirective>(strict = false) != null
    }

    /**
     * Analyze a Kotlin PSI file and extract semantic elements.
     */
    private fun analyzeKotlinFile(ktFile: org.jetbrains.kotlin.psi.KtFile): List<EditorSemanticElement> {
        val elements = mutableListOf<EditorSemanticElement>()

        ktFile.accept(object : KtTreeVisitorVoid() {

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                // Skip import statements
                if (isInsideImport(expression)) return

                // Get the callee expression (function name)
                val callee = expression.calleeExpression
                if (callee != null) {
                    elements.add(EditorSemanticElement(
                        startOffset = callee.startOffset,
                        endOffset = callee.endOffset,
                        type = SemanticType.FUNCTION_CALL,
                        name = callee.text
                    ))
                }
            }

            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(expression)

                // Skip import statements
                if (isInsideImport(expression)) return

                // Check if the selector is a property access (not a function call)
                val selector = expression.selectorExpression
                if (selector != null && selector !is KtCallExpression) {
                    // This is a property access like obj.property
                    elements.add(EditorSemanticElement(
                        startOffset = selector.startOffset,
                        endOffset = selector.endOffset,
                        type = SemanticType.PROPERTY_ACCESS,
                        name = selector.text
                    ))
                }
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                super.visitReferenceExpression(expression)

                // Skip import statements
                if (isInsideImport(expression)) return

                // Skip if already handled as part of other expressions
                val parent = expression.parent
                if (parent is KtCallExpression && parent.calleeExpression == expression) {
                    return // Already handled as function call
                }
                if (parent is KtDotQualifiedExpression && parent.selectorExpression == expression) {
                    return // Already handled as property access
                }

                // Check if this is a class/type reference (starts with uppercase)
                val name = expression.text
                if (name.isNotEmpty() && name[0].isUpperCase()) {
                    // Could be a class reference, object reference, or enum
                    val grandParent = parent?.parent
                    if (grandParent is KtDotQualifiedExpression && grandParent.receiverExpression == parent) {
                        // This is the receiver in a qualified expression (e.g., MyClass.something)
                        elements.add(EditorSemanticElement(
                            startOffset = expression.startOffset,
                            endOffset = expression.endOffset,
                            type = SemanticType.OBJECT_REFERENCE,
                            name = name
                        ))
                    }
                }
            }

            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                super.visitAnnotationEntry(annotationEntry)

                // Get annotation name
                val typeRef = annotationEntry.typeReference
                if (typeRef != null) {
                    elements.add(EditorSemanticElement(
                        startOffset = typeRef.startOffset,
                        endOffset = typeRef.endOffset,
                        type = SemanticType.ANNOTATION,
                        name = typeRef.text
                    ))
                }
            }

            override fun visitParameter(parameter: KtParameter) {
                super.visitParameter(parameter)

                // Function parameters
                val nameId = parameter.nameIdentifier
                if (nameId != null) {
                    elements.add(EditorSemanticElement(
                        startOffset = nameId.startOffset,
                        endOffset = nameId.endOffset,
                        type = SemanticType.PARAMETER,
                        name = nameId.text
                    ))
                }
            }

            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)

                // Local variables (properties inside functions)
                if (property.isLocal) {
                    val nameId = property.nameIdentifier
                    if (nameId != null) {
                        elements.add(EditorSemanticElement(
                            startOffset = nameId.startOffset,
                            endOffset = nameId.endOffset,
                            type = SemanticType.LOCAL_VARIABLE,
                            name = nameId.text
                        ))
                    }
                }
            }

            override fun visitLabeledExpression(expression: KtLabeledExpression) {
                super.visitLabeledExpression(expression)

                // Labels like @label
                val labelRef = expression.getTargetLabel()
                if (labelRef != null) {
                    elements.add(EditorSemanticElement(
                        startOffset = labelRef.startOffset,
                        endOffset = labelRef.endOffset,
                        type = SemanticType.LABEL,
                        name = labelRef.text
                    ))
                }
            }

            override fun visitTypeParameter(parameter: KtTypeParameter) {
                super.visitTypeParameter(parameter)

                val nameId = parameter.nameIdentifier
                if (nameId != null) {
                    elements.add(EditorSemanticElement(
                        startOffset = nameId.startOffset,
                        endOffset = nameId.endOffset,
                        type = SemanticType.TYPE_PARAMETER,
                        name = nameId.text
                    ))
                }
            }
        })

        return elements
    }
}
