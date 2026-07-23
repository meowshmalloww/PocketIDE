package org.nehuatl.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLibrarySelectorTest {
    @Test
    fun `baseline arm does not infer v82 from crypto flags`() {
        val selected = NativeLibrarySelector.select("arm64-v8a", "Features: fp asimd aes crc32")

        assertEquals("librnllama_v8.so", selected.reportName)
    }

    @Test
    fun `dot product arm selects matching optimized library`() {
        val selected = NativeLibrarySelector.select("arm64-v8a", "Features: asimd asimddp")

        assertEquals("librnllama_v8_2_dotprod.so", selected.reportName)
    }

    @Test
    fun `i8mm and dot product arm selects combined optimized library`() {
        val selected = NativeLibrarySelector.select("arm64-v8a", "Features: asimd asimddp i8mm")

        assertEquals("librnllama_v8_2_dotprod_i8mm.so", selected.reportName)
    }

    @Test
    fun `x86 keeps its dedicated library`() {
        val selected = NativeLibrarySelector.select("x86_64", "")

        assertEquals("librnllama_x86_64.so", selected.reportName)
    }
}
