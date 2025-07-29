package com.voropai.labs.audiotracer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageManagerTest {

    @Test
    fun `file rolling constants are properly defined`() {
        // Test that constants are reasonable values
        assertTrue(Constants.Audio.MAX_FILE_SIZE_BYTES > 0)
        assertTrue(Constants.Audio.MAX_FILE_DURATION_MS > 0)
        
        // 500 MB should be less than 2GB to avoid container limits
        assertTrue(Constants.Audio.MAX_FILE_SIZE_BYTES < 2L * 1024 * 1024 * 1024)
        
        // 1 hour should be reasonable for file duration
        assertTrue(Constants.Audio.MAX_FILE_DURATION_MS <= 60 * 60 * 1000)
        
        // Verify the exact values we expect
        assertEquals(50L * 1024, Constants.Audio.MAX_FILE_SIZE_BYTES)
        assertEquals(60 * 60 * 1000L, Constants.Audio.MAX_FILE_DURATION_MS)
    }
} 