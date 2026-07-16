package com.pocketide.data.execution

import com.pocketide.data.model.CodeFile
import fi.iki.elonen.NanoHTTPD

/** Serves an in-memory project snapshot only on the device loopback interface. */
class ProjectPreviewServer(
    private val codeExecutor: CodeExecutor,
) {
    private var server: NanoHTTPD? = null

    fun start(files: List<CodeFile>, activeFile: CodeFile?): Result<String> = runCatching {
        require(files.isNotEmpty()) { "The project has no files to preview" }
        stop()
        val snapshot = files.associate { it.name.replace('\\', '/') to it.content }
        val entryPoint = when {
            "index.html" in snapshot -> "index.html"
            activeFile?.name?.endsWith(".html", true) == true -> activeFile.name
            else -> "__pocketide_preview__.html"
        }
        val previewHtml = if (entryPoint.startsWith("__")) createPreviewDocument(files, activeFile) else null

        var lastError: Throwable? = null
        for (port in PORT_RANGE) {
            val candidate = object : NanoHTTPD(LOOPBACK_HOST, port) {
                override fun serve(session: IHTTPSession): Response {
                    val requested = session.uri.substringBefore('?').trimStart('/').ifBlank { entryPoint }
                    if (requested.contains("..") || requested.contains('\\')) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
                    }
                    val source = if (requested == "__pocketide_preview__.html") previewHtml else snapshot[requested]
                    if (source == null) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                    }
                    val body = if (requested.endsWith(".ts", true)) {
                        codeExecutor.transpileTypeScriptForBrowser(source)
                    } else {
                        source
                    }
                    val mime = when {
                        requested.endsWith(".html", true) -> MIME_HTML
                        requested.endsWith(".css", true) -> "text/css"
                        requested.endsWith(".js", true) || requested.endsWith(".ts", true) -> "application/javascript"
                        requested.endsWith(".json", true) -> "application/json"
                        requested.endsWith(".svg", true) -> "image/svg+xml"
                        else -> "text/plain"
                    }
                    return newFixedLengthResponse(Response.Status.OK, mime, body).apply {
                        addHeader("Cache-Control", "no-store")
                        addHeader("X-Content-Type-Options", "nosniff")
                    }
                }
            }
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = candidate
                return@runCatching "http://$LOOPBACK_HOST:$port/"
            } catch (error: Throwable) {
                candidate.stop()
                lastError = error
            }
        }
        throw IllegalStateException("No local preview port is available", lastError)
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun createPreviewDocument(files: List<CodeFile>, activeFile: CodeFile?): String {
        val styles = files.filter { it.name.endsWith(".css", true) }
            .joinToString("\n") { "<link rel=\"stylesheet\" href=\"/${escapeAttribute(it.name)}\">" }
        val scripts = files.filter { it.name.endsWith(".js", true) || it.name.endsWith(".ts", true) }
        val selectedScripts = if (activeFile != null && activeFile in scripts) listOf(activeFile) else scripts
        val scriptTags = selectedScripts.joinToString("\n") {
            "<script src=\"/${escapeAttribute(it.name)}\"></script>"
        }
        return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketIDE Preview</title>$styles</head>
<body><main id="app"><h1>PocketIDE web preview</h1><p>Open or create index.html to control this page.</p></main>
$scriptTags</body></html>"""
    }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private val PORT_RANGE = 8765..8769
    }
}
