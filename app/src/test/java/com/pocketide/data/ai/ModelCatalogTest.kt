package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    @Test
    fun `catalog uses exact published artifacts and direct download URLs`() {
        assertEquals(2, ModelCatalog.entries.size)

        val qwen = ModelCatalog.qwenCoder.modelAsset
        assertEquals(1_066_227_264L, qwen.expectedBytes)
        assertEquals(
            "aa8353e0d0fca3a0041828701e90db7635197400f040676d11d7798665fa316e",
            qwen.sha256,
        )

        val llama = ModelCatalog.llamaSpinQuant
        assertEquals(1_135_951_488L, llama.modelAsset.expectedBytes)
        assertEquals(2_183_982L, llama.tokenizerAsset?.expectedBytes)
        ModelCatalog.entries.flatMap { it.assets }.forEach { asset ->
            assertTrue(asset.downloadUrl.contains("/resolve/main/"))
            assertEquals(64, asset.sha256.length)
        }
    }

    @Test
    fun `installed pte entry includes tokenizer and correct prompt template`() {
        val directory = File("models")
        val installed = ModelCatalog.llamaSpinQuant.installedModel(directory)

        assertTrue(installed.modelPath.endsWith(".pte"))
        assertTrue(installed.tokenizerPath.endsWith(".model"))
        assertEquals(PromptTemplate.LLAMA3, installed.promptTemplate)
        assertEquals(PromptTemplate.QWEN, ModelCatalog.qwenCoder.promptTemplate)
    }

    @Test
    fun `sha256 helper emits canonical lowercase digest`() {
        val file = kotlin.io.path.createTempFile().toFile()
        try {
            file.writeText("PocketIDE")
            assertEquals(
                "9d318a9a9c2853ecf4ef34699f8b28538e9c82f7c116d940d982d02754b4773d",
                file.sha256(),
            )
        } finally {
            file.delete()
        }
    }
}
