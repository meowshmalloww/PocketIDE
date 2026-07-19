package com.pocketide.data.ai

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProcessExitDiagnosticsTest {
    @Test
    fun `maps low memory record and converts sampled kilobytes`() {
        val exit = ProcessExitDiagnostics.mapRecord(
            reason = ApplicationExitInfo.REASON_LOW_MEMORY,
            timestampMs = 1234,
            status = 0,
            description = "lmkd",
            pssKb = 1024,
            rssKb = 2048,
        )

        requireNotNull(exit)
        assertEquals(ProcessExitCategory.LOW_MEMORY, exit.category)
        assertEquals(1024L * 1024L, exit.pssBytes)
        assertEquals(2048L * 1024L, exit.rssBytes)
        assertTrue(exit.userMessage().contains("smaller context"))
    }

    @Test
    fun `maps native crash anr and signal separately`() {
        assertEquals(
            ProcessExitCategory.NATIVE_CRASH,
            mappedCategory(ApplicationExitInfo.REASON_CRASH_NATIVE),
        )
        assertEquals(ProcessExitCategory.ANR, mappedCategory(ApplicationExitInfo.REASON_ANR))
        assertEquals(
            ProcessExitCategory.SIGNAL,
            mappedCategory(ApplicationExitInfo.REASON_SIGNALED),
        )
    }

    @Test
    fun `ignores normal user and package lifecycle exits`() {
        assertNull(record(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull(record(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertNull(record(ApplicationExitInfo.REASON_EXIT_SELF))
    }

    private fun mappedCategory(reason: Int): ProcessExitCategory = requireNotNull(record(reason)).category

    private fun record(reason: Int): PreviousProcessExit? = ProcessExitDiagnostics.mapRecord(
        reason = reason,
        timestampMs = 1,
        status = 9,
        description = null,
        pssKb = 0,
        rssKb = 0,
    )
}
