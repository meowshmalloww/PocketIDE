package com.pocketide.data.execution

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ProjectPreviewServerTest {
    private val server = ProjectPreviewServer(CodeExecutor())

    @After
    fun tearDown() = server.stop()

    @Test
    fun `serves html and transpiles simple typescript on loopback`() {
        val files = listOf(
            CodeFile(name = "index.html", language = Language.HTML, content = "<script src=\"/main.ts\"></script><h1>ok</h1>"),
            CodeFile(name = "main.ts", language = Language.TYPESCRIPT, content = "const answer: number = 42; console.log(answer);"),
        )

        val baseUrl = server.start(files, files.first()).getOrThrow()
        assertTrue(baseUrl.startsWith("http://127.0.0.1:"))
        assertTrue(URL(baseUrl).readText().contains("<h1>ok</h1>"))
        val transpiled = URL(baseUrl + "main.ts").readText()
        assertTrue(Regex("var\\s+answer\\s*=\\s*42").containsMatchIn(transpiled))
        assertFalse(transpiled.contains(": number"))
    }

    @Test
    fun `rejects traversal requests`() {
        val files = listOf(CodeFile(name = "index.html", language = Language.HTML, content = "ok"))
        val baseUrl = server.start(files, files.first()).getOrThrow()
        val responseCode = (URL(baseUrl + "%2e%2e/secret").openConnection() as java.net.HttpURLConnection).run {
            instanceFollowRedirects = false
            responseCode
        }
        assertTrue(responseCode == 403 || responseCode == 404)
    }
}
