package com.pocketide.data.ai

import java.io.File
import java.io.RandomAccessFile

/** Minimal, bounded GGUF header reader used before native model loading. */
internal object GgufMetadataReader {
    data class Metadata(
        val architecture: String,
        val name: String?,
        val sizeLabel: String?,
        val contextLength: Int,
        val embeddingLength: Int,
        val blockCount: Int,
        val attentionHeadCount: Int,
        val attentionKvHeadCount: Int,
        val attentionKeyLength: Int,
        val expertCount: Int?,
        val expertUsedCount: Int?,
    )

    fun read(file: File): Metadata? = runCatching {
        if (!file.isFile || file.length() < MIN_HEADER_BYTES) return null
        RandomAccessFile(file, "r").use { input ->
            val magic = ByteArray(4).also(input::readFully)
            if (!magic.contentEquals(GGUF_MAGIC)) return null
            val version = input.readUInt32Le().toInt()
            if (version !in SUPPORTED_VERSIONS) return null
            input.readUInt64Le() // tensor count
            val metadataCount = input.readUInt64Le()
            if (metadataCount !in 1..MAX_METADATA_ENTRIES) return null

            val values = mutableMapOf<String, Any>()
            repeat(metadataCount.toInt()) {
                val key = input.readGgufString(MAX_KEY_BYTES) ?: return null
                val type = input.readUInt32Le().toInt()
                if (key.startsWith("tokenizer.") && hasCoreArchitecture(values)) {
                    return@use buildMetadata(values)
                }
                if (isArchitectureKey(key)) {
                    input.readValue(type)?.let { values[key] = it }
                } else {
                    input.skipValue(type)
                }
                if (hasCompleteArchitecture(values)) {
                    return@use buildMetadata(values)
                }
            }
            buildMetadata(values)
        }
    }.getOrNull()

    private fun buildMetadata(values: Map<String, Any>): Metadata? {
        val architecture = values[GENERAL_ARCHITECTURE] as? String ?: return null
        val prefix = "$architecture."
        val embedding = values.longValue(prefix + "embedding_length")?.positiveInt() ?: return null
        val heads = values.longValue(prefix + "attention.head_count")?.positiveInt() ?: return null
        return Metadata(
            architecture = architecture,
            name = values[GENERAL_NAME] as? String,
            sizeLabel = values[GENERAL_SIZE_LABEL] as? String,
            contextLength = values.longValue(prefix + "context_length")?.positiveInt() ?: return null,
            embeddingLength = embedding,
            blockCount = values.longValue(prefix + "block_count")?.positiveInt() ?: return null,
            attentionHeadCount = heads,
            attentionKvHeadCount = values.longValue(prefix + "attention.head_count_kv")
                ?.positiveInt() ?: heads,
            attentionKeyLength = values.longValue(prefix + "attention.key_length")
                ?.positiveInt() ?: (embedding / heads).coerceAtLeast(1),
            expertCount = values.longValue(prefix + "expert_count")?.positiveInt(),
            expertUsedCount = values.longValue(prefix + "expert_used_count")?.positiveInt(),
        )
    }

    private fun hasCoreArchitecture(values: Map<String, Any>): Boolean {
        val architecture = values[GENERAL_ARCHITECTURE] as? String ?: return false
        val prefix = "$architecture."
        return listOf(
            "context_length",
            "embedding_length",
            "block_count",
            "attention.head_count",
        ).all { values.containsKey(prefix + it) }
    }

    private fun hasCompleteArchitecture(values: Map<String, Any>): Boolean {
        if (!hasCoreArchitecture(values)) return false
        val architecture = values[GENERAL_ARCHITECTURE] as String
        val attentionComplete = values.containsKey("$architecture.attention.head_count_kv") &&
            values.containsKey("$architecture.attention.key_length")
        val moeComplete = !architecture.contains("moe", ignoreCase = true) ||
            values.containsKey("$architecture.expert_count")
        return attentionComplete && moeComplete
    }

    private fun isArchitectureKey(key: String): Boolean =
        key == GENERAL_ARCHITECTURE || key == GENERAL_NAME || key == GENERAL_SIZE_LABEL ||
            ARCHITECTURE_SUFFIXES.any(key::endsWith)

    private fun Map<String, Any>.longValue(key: String): Long? = (get(key) as? Number)?.toLong()

    private fun Long.positiveInt(): Int? = takeIf { it in 1..Int.MAX_VALUE }?.toInt()

    private fun RandomAccessFile.readValue(type: Int): Any? = when (type) {
        UINT8 -> readUnsignedByte()
        INT8 -> readByte()
        UINT16 -> readUInt16Le()
        INT16 -> readUInt16Le().toShort()
        UINT32 -> readUInt32Le()
        INT32 -> readUInt32Le().toInt()
        FLOAT32 -> Float.fromBits(readUInt32Le().toInt())
        BOOL -> readUnsignedByte() != 0
        STRING -> readGgufString(MAX_VALUE_STRING_BYTES)
        UINT64, INT64 -> readUInt64Le()
        FLOAT64 -> Double.fromBits(readUInt64Le())
        ARRAY -> {
            skipValue(type)
            null
        }
        else -> error("Unknown GGUF metadata type $type")
    }

    private fun RandomAccessFile.skipValue(type: Int, depth: Int = 0) {
        require(depth <= MAX_ARRAY_DEPTH) { "GGUF metadata array nesting is too deep" }
        when (type) {
            UINT8, INT8, BOOL -> skipBounded(1)
            UINT16, INT16 -> skipBounded(2)
            UINT32, INT32, FLOAT32 -> skipBounded(4)
            UINT64, INT64, FLOAT64 -> skipBounded(8)
            STRING -> {
                val length = readUInt64Le()
                require(length in 0..MAX_SKIPPED_STRING_BYTES)
                skipBounded(length)
            }
            ARRAY -> {
                val elementType = readUInt32Le().toInt()
                val count = readUInt64Le()
                require(count in 0..MAX_ARRAY_ELEMENTS)
                val fixedSize = fixedTypeSize(elementType)
                if (fixedSize != null) {
                    skipBounded(Math.multiplyExact(count, fixedSize.toLong()))
                } else {
                    repeat(count.toInt()) { skipValue(elementType, depth + 1) }
                }
            }
            else -> error("Unknown GGUF metadata type $type")
        }
    }

    private fun RandomAccessFile.readGgufString(maxBytes: Long): String? {
        val length = readUInt64Le()
        if (length !in 0..maxBytes || length > Int.MAX_VALUE) return null
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.readUInt16Le(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        var value = 0L
        repeat(4) { shift -> value = value or (readUnsignedByte().toLong() shl (shift * 8)) }
        return value
    }

    private fun RandomAccessFile.readUInt64Le(): Long {
        var value = 0L
        repeat(8) { shift -> value = value or (readUnsignedByte().toLong() shl (shift * 8)) }
        return value
    }

    private fun RandomAccessFile.skipBounded(bytes: Long) {
        require(bytes >= 0L && filePointer + bytes <= length())
        seek(filePointer + bytes)
    }

    private fun fixedTypeSize(type: Int): Int? = when (type) {
        UINT8, INT8, BOOL -> 1
        UINT16, INT16 -> 2
        UINT32, INT32, FLOAT32 -> 4
        UINT64, INT64, FLOAT64 -> 8
        else -> null
    }

    private const val GENERAL_ARCHITECTURE = "general.architecture"
    private const val GENERAL_NAME = "general.name"
    private const val GENERAL_SIZE_LABEL = "general.size_label"
    private val ARCHITECTURE_SUFFIXES = listOf(
        ".context_length",
        ".embedding_length",
        ".block_count",
        ".attention.head_count",
        ".attention.head_count_kv",
        ".attention.key_length",
        ".expert_count",
        ".expert_used_count",
    )

    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private val SUPPORTED_VERSIONS = 2..3
    private const val MIN_HEADER_BYTES = 24L
    private const val MAX_METADATA_ENTRIES = 1_000_000L
    private const val MAX_KEY_BYTES = 65_535L
    private const val MAX_VALUE_STRING_BYTES = 4L * 1024 * 1024
    private const val MAX_SKIPPED_STRING_BYTES = 64L * 1024 * 1024
    private const val MAX_ARRAY_ELEMENTS = 2_000_000L
    private const val MAX_ARRAY_DEPTH = 4

    private const val UINT8 = 0
    private const val INT8 = 1
    private const val UINT16 = 2
    private const val INT16 = 3
    private const val UINT32 = 4
    private const val INT32 = 5
    private const val FLOAT32 = 6
    private const val BOOL = 7
    private const val STRING = 8
    private const val ARRAY = 9
    private const val UINT64 = 10
    private const val INT64 = 11
    private const val FLOAT64 = 12
}
