package com.deepsleep.chat.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateRepository.isNewer("0.2.1", "0.2.0"))
        assertTrue(UpdateRepository.isNewer("1.0", "0.9.9"))
        assertTrue(UpdateRepository.isNewer("0.3", "0.2.9"))
        assertFalse(UpdateRepository.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateRepository.isNewer("0.2.0", "0.2.0-beta"))
        assertFalse(UpdateRepository.isNewer("0.2.0", "0.2"))
        assertEquals(0, UpdateRepository.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun extractsVersionFromReleaseTags() {
        assertEquals("0.2.1", UpdateRepository.versionFromTag("v0.2.1"))
        assertEquals("0.2.1", UpdateRepository.versionFromTag("0.2.1"))
        assertEquals("0.2.1", UpdateRepository.versionFromTag("DeepSleep-0.2.1"))
        assertNull(UpdateRepository.versionFromTag("latest"))
    }

    @Test
    fun normalizesGitHubRepoInputs() {
        assertEquals("bear561/deepsleep", UpdateRepository.normalizeRepo("bear561/deepsleep"))
        assertEquals(
            "bear561/deepsleep",
            UpdateRepository.normalizeRepo("https://github.com/bear561/deepsleep.git"),
        )
        assertEquals(
            "bear561/deepsleep",
            UpdateRepository.normalizeRepo("github.com/bear561/deepsleep/"),
        )
        assertNull(UpdateRepository.normalizeRepo("not-a-repo"))
        assertNull(UpdateRepository.normalizeRepo("owner/repo/extra"))
        assertNull(UpdateRepository.normalizeRepo("owner/../repo"))
    }
}