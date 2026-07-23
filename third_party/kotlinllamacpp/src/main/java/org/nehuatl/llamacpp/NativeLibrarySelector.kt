package org.nehuatl.llamacpp

/** Pure, testable selection of the native library whose ISA matches the Android CPU. */
object NativeLibrarySelector {
    data class Selection(
        val jniName: String,
        val reportName: String = "lib$jniName.so",
    )

    fun select(primaryAbi: String?, cpuFeatures: String): Selection {
        if (primaryAbi == "x86_64") return Selection("rnllama_x86_64")
        if (primaryAbi != "arm64-v8a") return Selection("rnllama")

        val features = cpuFeatures.lowercase()
        val hasDotProd = "dotprod" in features || "asimddp" in features
        val hasI8mm = "i8mm" in features
        return when {
            hasDotProd && hasI8mm -> Selection("rnllama_v8_2_dotprod_i8mm")
            hasDotProd -> Selection("rnllama_v8_2_dotprod")
            hasI8mm -> Selection("rnllama_v8_2_i8mm")
            // AES and CRC32 are optional Armv8.0 features and do not prove Armv8.2.
            // Fall back to the baseline Armv8 build when neither v8.2-era inference
            // feature is reported, avoiding an unsafe instruction-set assumption.
            else -> Selection("rnllama_v8")
        }
    }
}
