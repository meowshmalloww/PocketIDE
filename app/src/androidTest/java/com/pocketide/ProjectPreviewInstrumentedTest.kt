package com.pocketide

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.data.execution.CodeExecutor
import com.pocketide.data.execution.ProjectPreviewServer
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ProjectPreviewInstrumentedTest {
    @Test
    fun loopbackServerServesProjectInsideAndroid() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = ProjectPreviewServer(CodeExecutor(context))
        try {
            val files = listOf(
                CodeFile(name = "index.html", language = Language.HTML, content = "<h1>Android preview works</h1>"),
                CodeFile(name = "main.ts", language = Language.TYPESCRIPT, content = "const value: number = 7;"),
            )
            val baseUrl = server.start(files, files.first()).getOrThrow()
            assertTrue(baseUrl.startsWith("http://127.0.0.1:"))
            assertTrue(URL(baseUrl).readText().contains("Android preview works"))
            val typescript = URL(baseUrl + "main.ts").readText()
            assertFalse(typescript.contains(": number"))
        } finally {
            server.stop()
        }
    }
}
