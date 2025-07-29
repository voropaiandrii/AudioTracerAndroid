package com.voropai.labs.audiotracer

object Constants {
    object Audio {
        val SAMPLE_RATE: Int = 24000 // 24 KHz
        val ENCODING_BIT_RATE: Int = 16000 // 16 kbps
        
        // File rolling configuration
        //const val MAX_FILE_SIZE_BYTES: Long = 50 * 1024  // 50 KB per file (for testing)
        const val MAX_FILE_SIZE_BYTES: Long = 50 * 1024 * 1024  // 50 MB per file

        // TODO: It ignores the max file size limit at the moment
        //const val MAX_FILE_DURATION_MS: Long = 30 * 1000    // 30 seconds per file (optional)
        const val MAX_FILE_DURATION_MS: Long = 60 * 60 * 1000    // 1 hour per file (optional)
    }
}