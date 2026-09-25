package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorDocument
import ai.rever.bosseditor.features.MinimapRenderer
import ai.rever.bosseditor.features.MinimapState
import ai.rever.bosseditor.fold.VisualLineMapper
import ai.rever.bosseditor.highlight.TokenProvider
import ai.rever.bosseditor.theme.EditorColors
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EditorRenderingCompatibilityTest {
    private fun isolatedLoader() = object : URLClassLoader(
        arrayOf(MinimapRenderer::class.java.protectionDomain.codeSource.location),
        javaClass.classLoader
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("org.jetbrains.skia.") || name.startsWith("org.jetbrains.skiko.")) {
                throw ClassNotFoundException("Rendering internals blocked: $name")
            }
            if (name.startsWith("ai.rever.bosseditor.features.MinimapRenderer") ||
                name.startsWith("ai.rever.bosseditor.features.MinimapCanvas") ||
                name.startsWith("ai.rever.bosseditor.settings.FontUtilsKt")) {
                return synchronized(getClassLoadingLock(name)) {
                    (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun `packaged editor contains no direct Skia references or bundled runtime`() {
        java.util.zip.ZipFile(System.getProperty("editor.plugin.jar")).use { jar ->
            for (entry in jar.entries()) {
                assertFalse(entry.name.startsWith("org/jetbrains/skia/") ||
                    entry.name.startsWith("org/jetbrains/skiko/"), entry.name)
                if (entry.name.startsWith("ai/rever/bosseditor/") && entry.name.endsWith(".class")) {
                    val bytes = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    assertFalse(bytes.contains("org/jetbrains/skia/") ||
                        bytes.contains("org/jetbrains/skiko/"), entry.name)
                }
            }
        }
    }

    @Test
    fun `minimap renders pixels with direct Skia access blocked`() {
        isolatedLoader().use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.Canvas") }
            // Resolve composable signatures too: none may expose a native canvas.
            loader.loadClass("ai.rever.bosseditor.features.MinimapCanvasKt").declaredMethods
            val rendererClass = loader.loadClass("ai.rever.bosseditor.features.MinimapRenderer")
            val document = EditorDocument("hello\nworld")
            val colors = EditorTheme.Dark.colors
            val renderer = rendererClass.getConstructor(
                EditorDocument::class.java, TokenProvider::class.java,
                EditorColors::class.java, VisualLineMapper::class.java
            ).newInstance(document, null, colors, VisualLineMapper.noFolds(document.lineCount))
            val bitmap = ImageBitmap(80, 80)
            rendererClass.getMethod("render", Canvas::class.java, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, MinimapState::class.java
            ).invoke(renderer, Canvas(bitmap), 0f, 0f, 80f, 80f,
                MinimapState(visibleLineCount = 1, currentLine = 0))
            val pixels = bitmap.toPixelMap()
            assertEquals(colors.background, pixels[40, 70])
            assertTrue(pixels[3, 0] != colors.background, "Document content must be painted")
        }
    }

    @Test
    fun `font enumeration selection and missing font fallback work without Skia access`() {
        isolatedLoader().use { loader ->
            val fonts = loader.loadClass("ai.rever.bosseditor.settings.FontUtilsKt")
            val categorized = fonts.getMethod("getEditorCategorizedFonts").invoke(null) as Map<*, *>
            assertEquals(setOf("Recommended", "Fixed Pitch", "Variable Pitch"), categorized.keys)
            val installed = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.first()
            assertEquals(true, fonts.getMethod("isFontInstalled", String::class.java).invoke(null, installed))
            assertIs<FontFamily>(fonts.getMethod("loadEditorFont", String::class.java).invoke(null, installed))
            assertIs<FontFamily>(fonts.getMethod("loadEditorFont", String::class.java).invoke(null, null))
            val missing = "BOSS definitely missing font 3e3598a6"
            assertFalse(fonts.getMethod("isFontInstalled", String::class.java).invoke(null, missing) as Boolean)
            assertEquals(FontFamily.Monospace, fonts.getMethod("loadEditorFont", String::class.java).invoke(null, missing))
        }
    }
}
