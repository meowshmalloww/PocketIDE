package com.pocketide.data.ai

import android.os.Environment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelFileImporterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var importer: ModelFileImporter
    private lateinit var catalogDirectory: File

    @Before
    fun setUp() {
        importer = ModelFileImporter(context)
        catalogDirectory = File(
            checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "models",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        catalogDirectory.deleteRecursively()
    }

    @Test
    fun `deletes catalog files from managed external storage`() {
        val downloaded = File(catalogDirectory, "model.gguf").apply { writeText("model") }

        assertTrue(importer.deleteImported(downloaded.absolutePath))
        assertFalse(downloaded.exists())
    }

    @Test
    fun `does not delete files outside managed model roots`() {
        val unrelated = File(context.cacheDir, "keep.txt").apply { writeText("keep") }
        try {
            assertFalse(importer.deleteImported(unrelated.absolutePath))
            assertTrue(unrelated.exists())
        } finally {
            unrelated.delete()
        }
    }
}
