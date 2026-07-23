package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class GgufMetadataReaderTest {
    @Test
    fun `model spec reads architecture from gguf metadata instead of filename`() {
        val file = File.createTempFile("neutral-model", ".gguf")
        try {
            file.writeBytes(
                gguf(
                    "general.architecture" to stringValue("qwen2"),
                    "general.name" to stringValue("Qwen 2.5 Coder 1.5B"),
                    "general.size_label" to stringValue("1.5B"),
                    "qwen2.context_length" to uint32Value(32_768),
                    "qwen2.embedding_length" to uint32Value(1_536),
                    "qwen2.block_count" to uint32Value(28),
                    "qwen2.attention.head_count" to uint32Value(12),
                    "qwen2.attention.head_count_kv" to uint32Value(2),
                    "qwen2.attention.key_length" to uint32Value(128),
                ),
            )

            val architecture = ModelSpec.detect(file.absolutePath)

            assertEquals("GGUF metadata", architecture.source)
            assertEquals("Qwen 2.5 Coder 1.5B", architecture.displayName)
            assertEquals(1.5f, architecture.paramCountBillion)
            assertEquals(28, architecture.numLayers)
            assertEquals(1_536, architecture.hiddenDim)
            assertEquals(2, architecture.numKvHeads)
            assertEquals(128, architecture.headDim)
            assertEquals(32_768, architecture.maxContextLength)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `invalid gguf falls back without throwing`() {
        val file = File.createTempFile("qwen-1.5b", ".gguf")
        try {
            file.writeText("not a gguf")
            val architecture = ModelSpec.detect(file.absolutePath)
            assertEquals("filename architecture table", architecture.source)
            assertEquals(28, architecture.numLayers)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `PowerMoE metadata keeps the official four thousand token limit`() {
        val file = File.createTempFile("PowerMoE-3b-Q4_K_S", ".gguf")
        try {
            file.writeBytes(
                gguf(
                    "general.architecture" to stringValue("granitemoe"),
                    "general.name" to stringValue("PowerMoE-3B"),
                    "general.size_label" to stringValue("3B"),
                    "granitemoe.context_length" to uint32Value(4_096),
                    "granitemoe.embedding_length" to uint32Value(1_536),
                    "granitemoe.block_count" to uint32Value(32),
                    "granitemoe.expert_count" to uint32Value(40),
                    "granitemoe.expert_used_count" to uint32Value(8),
                    "granitemoe.attention.head_count" to uint32Value(24),
                    "granitemoe.attention.head_count_kv" to uint32Value(8),
                    "granitemoe.attention.key_length" to uint32Value(64),
                ),
            )

            val architecture = ModelSpec.detect(file.absolutePath)

            assertEquals("GGUF metadata", architecture.source)
            assertEquals("PowerMoE-3B", architecture.displayName)
            assertEquals(3.0f, architecture.paramCountBillion)
            assertEquals(4_096, architecture.maxContextLength)
            assertEquals(32, architecture.numLayers)
            assertEquals(8, architecture.numKvHeads)
            assertEquals(64, architecture.headDim)
            assertEquals("granitemoe", architecture.architectureId)
            assertEquals(40, architecture.expertCount)
            assertEquals(8, architecture.expertUsedCount)
            assertEquals(true, architecture.isMixtureOfExperts)
        } finally {
            file.delete()
        }
    }

    private fun gguf(vararg entries: Pair<String, EncodedValue>): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            writeUInt32Le(3)
            writeUInt64Le(0)
            writeUInt64Le(entries.size.toLong())
            entries.forEach { (key, value) ->
                writeString(key)
                writeUInt32Le(value.type)
                write(value.bytes)
            }
        }.toByteArray()

    private fun stringValue(value: String): EncodedValue = EncodedValue(
        type = 8,
        bytes = ByteArrayOutputStream().apply { writeString(value) }.toByteArray(),
    )

    private fun uint32Value(value: Int): EncodedValue = EncodedValue(
        type = 4,
        bytes = ByteArrayOutputStream().apply { writeUInt32Le(value) }.toByteArray(),
    )

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeUInt64Le(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeUInt32Le(value: Int) {
        repeat(4) { shift -> write(value ushr (shift * 8) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeUInt64Le(value: Long) {
        repeat(8) { shift -> write((value ushr (shift * 8) and 0xff).toInt()) }
    }

    private data class EncodedValue(val type: Int, val bytes: ByteArray)
}
