package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.FileReadResult
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools contributed by the Code Editor Tab plugin: read/write files through
 * the host editor infrastructure and detect a file's language. Registered in
 * [EditorTabDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class EditorTabMcpToolProvider(
    override val providerId: String,
    private val editor: EditorContentProvider?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "editor_read_file",
            description = "Read a file's contents via the BOSS editor (handles large-file limits).",
            inputSchema = pathSchema("File path to read."),
            handler = McpToolHandler { args ->
                val e = editor ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                when (val r = e.readFileContent(path)) {
                    is FileReadResult.Success -> McpToolResult(r.content)
                    is FileReadResult.FileTooLarge ->
                        McpToolResult("File too large: ${r.sizeBytes} bytes (max ${r.maxSizeBytes}).", isError = true)
                    is FileReadResult.Error -> McpToolResult("Read failed: ${r.message}", isError = true)
                    FileReadResult.FileNotFound -> McpToolResult("File not found: $path", isError = true)
                }
            },
        ),
        McpToolDefinition(
            name = "editor_write_file",
            description = "Write (create or overwrite) a file via the BOSS editor.",
            inputSchema = WRITE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val e = editor ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                val content = args.string("content") ?: ""
                if (e.writeFileContent(path, content)) McpToolResult("Wrote ${content.length} chars to $path.")
                else McpToolResult("Write failed for $path.", isError = true)
            },
        ),
        McpToolDefinition(
            name = "editor_detect_language",
            description = "Detect the programming language the editor would use for a file path.",
            inputSchema = pathSchema("File path."),
            handler = McpToolHandler { args ->
                val e = editor ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                McpToolResult(e.detectLanguage(path))
            },
        ),
    )

    private fun unavailable(): McpToolResult =
        McpToolResult("Editor content provider unavailable in this context.", isError = true)

    private fun pathSchema(desc: String): String =
        """{"type":"object","properties":{"path":{"type":"string","description":"$desc"}},"required":["path"]}"""

    private companion object {
        const val WRITE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path to write."},"content":{"type":"string","description":"New file content."}},"required":["path","content"]}"""
    }
}
