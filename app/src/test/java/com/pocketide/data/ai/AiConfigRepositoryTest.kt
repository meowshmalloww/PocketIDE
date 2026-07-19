package com.pocketide.data.ai

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiConfigRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: AiConfigRepository

    @Before
    fun setUp() {
        preferences().edit().clear().commit()
        repository = AiConfigRepository(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `upsert adds and activates a verified model`() {
        repository.upsertAndActivate(
            ModelEntry("Qwen", "/models/qwen.gguf", promptTemplate = PromptTemplate.QWEN),
            Quantization.INT4,
        )

        val saved = repository.load()
        assertEquals(1, saved.models.size)
        assertEquals(0, saved.activeModelIndex)
        assertEquals(Quantization.INT4, saved.quantization)
        assertEquals(PromptTemplate.QWEN, saved.models.single().promptTemplate)
    }

    @Test
    fun `upsert replaces an existing path without duplicating it`() {
        repository.save(
            AiConfig(
                models = listOf(
                    ModelEntry("Old", "/models/model.pte", "/models/old-tokenizer.model"),
                    ModelEntry("Other", "/models/other.gguf"),
                ),
                activeModelIndex = 1,
            ),
        )

        repository.upsertAndActivate(
            ModelEntry("Updated", "/models/model.pte", "/models/tokenizer.model"),
            Quantization.INT4,
        )

        val saved = repository.load()
        assertEquals(2, saved.models.size)
        assertEquals("Updated", saved.models[0].name)
        assertEquals("/models/tokenizer.model", saved.models[0].tokenizerPath)
        assertEquals(0, saved.activeModelIndex)
    }

    private fun preferences() =
        context.getSharedPreferences("pocketide_prefs", Context.MODE_PRIVATE)
}
