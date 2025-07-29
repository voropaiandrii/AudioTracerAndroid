package com.voropai.labs.audiotracer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRollingTest {

    @Test
    fun `file rolling constants are reasonable for testing`() {
        // Test that the file size is reasonable for testing
        val maxFileSize = Constants.Audio.MAX_FILE_SIZE_BYTES
        val maxDuration = Constants.Audio.MAX_FILE_DURATION_MS
        
        // File size should be reasonable for testing (50KB)
        assertTrue(maxFileSize >= 50 * 1024, "File size should be at least 50KB for testing")
        
        // File size should be less than 1MB for testing
        assertTrue(maxFileSize < 1024 * 1024, "File size should be less than 1MB for testing")
        
        // Duration should be reasonable (1 hour max)
        assertTrue(maxDuration <= 60 * 60 * 1000, "Duration should be 1 hour or less")
        
        // Verify current values
        assertEquals(50 * 1024L, maxFileSize, "Expected 50KB file size")
        assertEquals(60 * 60 * 1000L, maxDuration, "Expected 1 hour duration")
    }

    @Test
    fun `file size calculation is correct`() {
        val fiftyKB = 50 * 1024L
        val oneHour = 60 * 60 * 1000L
        
        assertEquals(fiftyKB, Constants.Audio.MAX_FILE_SIZE_BYTES)
        assertEquals(oneHour, Constants.Audio.MAX_FILE_DURATION_MS)
    }
} 