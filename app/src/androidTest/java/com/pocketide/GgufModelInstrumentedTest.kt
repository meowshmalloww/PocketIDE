package com.pocketide

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Debug
import android.util.Log
import com.pocketide.data.ai.DeviceMemorySnapshot
import com.pocketide.data.ai.AiConfig
import com.pocketide.data.ai.AiService
import com.pocketide.data.ai.ExecutorchLlmRunner
import com.pocketide.data.ai.InferenceResourcePlanner
import com.pocketide.data.ai.InferenceResourceRequest
import com.pocketide.data.ai.LlamaCppRunner
import com.pocketide.data.ai.LlmRunner
import com.pocketide.data.ai.ModelFormat
import com.pocketide.data.ai.ModelEntry
import com.pocketide.data.ai.ModelSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real JNI smoke test for a provisioned GGUF. The test model is intentionally not committed.
 * Copy one to the target app's files directory as model-smoke.gguf before running this class.
 */
@RunWith(AndroidJUnit4::class)
class GgufModelInstrumentedTest {
    @Test
    fun realGgufLoadsGeneratesAndReleases() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuredPath = InstrumentationRegistry.getArguments().getString("gguf_model_path")
        val requestedContext = InstrumentationRegistry.getArguments()
            .getString("gguf_context")
            ?.toIntOrNull()
            ?.coerceAtLeast(512)
            ?: 512
        val model = configuredPath?.let(::File) ?: File(context.filesDir, "model-smoke.gguf")
        assumeTrue("Provision a real GGUF with gguf_model_path to run this test", model.isFile)

        val plan = InferenceResourcePlanner.plan(
            request = InferenceResourceRequest(
                format = ModelFormat.GGUF,
                requestedContext = requestedContext,
                requestedOutputTokens = 64,
                selectedThreads = 1,
                modelSizeBytes = model.length(),
                architecture = ModelSpec.detect(model.absolutePath),
                modelAlreadyLoaded = false,
            ),
            memory = DeviceMemorySnapshot.capture(context),
        )
        assertTrue("Real GGUF was rejected by the live resource plan: ${plan.blockingMessage()}", plan.allowed)
        Log.i(TAG, "Plan: ${plan.reason}; available=${plan.availableMemoryBytes}; required=${plan.requiredHeadroomBytes}")

        val runner = LlamaCppRunner(context)
        try {
            val load = runner.ensureLoaded(
                modelPath = model.absolutePath,
                tokenizerPath = "",
                temperature = 0.0f,
                options = LlmRunner.LoadOptions(
                    contextLength = plan.effectiveContext,
                    batchSize = plan.batchSize,
                    threadCount = plan.selectedThreads,
                    kvCacheTypeK = enumValueOf(plan.kvCacheTypeK.uppercase()),
                    kvCacheTypeV = enumValueOf(plan.kvCacheTypeV.uppercase()),
                    flashAttention = plan.flashAttention,
                ),
            )
            assertTrue("Real GGUF load failed: $load", load is LlmRunner.LoadResult.Success)
            Log.i(TAG, "PSS after load: ${Debug.getPss()} KiB")

            val generation = runner.generate(
                prompt = "Reply with only the word OK.",
                seqLen = 8,
                sink = LlmRunner.TokenSink { },
                options = LlmRunner.GenerationOptions(deterministic = true),
            )
            assertTrue(
                "Real GGUF generation failed: $generation",
                generation is LlmRunner.GenerateResult.Success && generation.text.isNotBlank(),
            )
            Log.i(TAG, "PSS after generation: ${Debug.getPss()} KiB")
        } finally {
            runner.release()
        }
        Unit
    }

    @Test
    fun selectingAnotherModelReleasesThePreviousNativeMappingBeforePlanning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "model-smoke.gguf")
        assumeTrue("Provision files/model-smoke.gguf to run the switch test", model.isFile)

        val runner = LlamaCppRunner(context)
        try {
            val load = runner.ensureLoaded(
                modelPath = model.absolutePath,
                tokenizerPath = "",
                temperature = 0.0f,
                options = LlmRunner.LoadOptions(contextLength = 512, batchSize = 64, threadCount = 1),
            )
            assertTrue("Initial real GGUF load failed: $load", load is LlmRunner.LoadResult.Success)

            val replacement = File(context.filesDir, "replacement-1.5b-q4_0.gguf")
            val service = AiService(
                executorchRunner = ExecutorchLlmRunner(),
                llamaCppRunner = runner,
                config = AiConfig(
                    models = listOf(ModelEntry("replacement", replacement.absolutePath)),
                    temperature = 0.0f,
                    contextWindowSize = 512,
                ),
                context = context,
            )
            service.prepareResourcePlan()

            assertFalse("The previous GGUF stayed resident while planning its replacement", runner.hasLoadedModel())
        } finally {
            runner.release()
        }
        Unit
    }

    private companion object {
        const val TAG = "GgufModelSmoke"
    }
}
