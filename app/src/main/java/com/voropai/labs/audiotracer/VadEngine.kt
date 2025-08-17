package com.voropai.labs.audiotracer

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.annotation.SuppressLint
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice Activity Detection engine interface
 */
interface VadEngine {
    data class Decision(val start: Boolean, val stop: Boolean)
    
    fun process(frame: ShortArray, n: Int): Decision
    fun reset()
    
    companion object {
        const val FRAME_MS = 20
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320 samples
        
        fun default(): VadEngine = EnergyBasedVad()
    }
}

/**
 * Energy-based VAD implementation using RMS and zero-crossing rate
 */
class EnergyBasedVad : VadEngine {
    private var speechStartCount = 0
    private var speechStopCount = 0
    private var noiseFloor = -50.0 // dBFS
    private val rmsHistory = mutableListOf<Double>()
    private val maxHistorySize = 250 // 5 seconds at 20ms frames
    
    companion object {
        private const val SPEECH_START_THRESHOLD_MS = 200 // 10 frames
        private const val SPEECH_STOP_THRESHOLD_MS = 1000  // 40 frames
        private const val SPEECH_START_FRAMES = SPEECH_START_THRESHOLD_MS / VadEngine.FRAME_MS
        private const val SPEECH_STOP_FRAMES = SPEECH_STOP_THRESHOLD_MS / VadEngine.FRAME_MS
        
        private const val SPEECH_START_DB_MARGIN = 12.0
        private const val SPEECH_STOP_DB_MARGIN = 6.0
        private const val MIN_NOISE_FLOOR = -70.0
        private const val MAX_NOISE_FLOOR = -35.0
        private const val MIN_SPEECH_THRESHOLD = -45.0
        private const val MIN_SILENCE_THRESHOLD = -50.0
    }
    
    override fun process(frame: ShortArray, n: Int): VadEngine.Decision {
        if (n <= 0) return VadEngine.Decision(false, false)
        
        val rms = calculateRMS(frame, n)
        val rmsDb = 20 * log10(rms / 32768.0)
        
        // Update noise floor using 30th percentile of recent RMS values
        updateNoiseFloor(rmsDb)
        
        // Calculate thresholds
        val speechStartThreshold = maxOf(noiseFloor + SPEECH_START_DB_MARGIN, MIN_SPEECH_THRESHOLD)
        val speechStopThreshold = maxOf(noiseFloor + SPEECH_STOP_DB_MARGIN, MIN_SILENCE_THRESHOLD)
        
        // Check zero-crossing rate to reject periodic hums
        val zcr = calculateZeroCrossingRate(frame, n)
        val validZcr = zcr in 0.02..0.20
        
        val isSpeech = rmsDb >= speechStartThreshold && validZcr
        
        // Update speech detection counters
        if (isSpeech) {
            speechStartCount++
            speechStopCount = 0
        } else {
            speechStopCount++
            speechStartCount = 0
        }
        
        val speechStart = speechStartCount >= SPEECH_START_FRAMES
        val speechStop = speechStopCount >= SPEECH_STOP_FRAMES
        
        return VadEngine.Decision(speechStart, speechStop)
    }
    
    override fun reset() {
        speechStartCount = 0
        speechStopCount = 0
        rmsHistory.clear()
        noiseFloor = -50.0
    }
    
    private fun calculateRMS(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            sum += frame[i] * frame[i]
        }
        return sqrt(sum / n)
    }
    
    private fun calculateZeroCrossingRate(frame: ShortArray, n: Int): Double {
        if (n < 2) return 0.0
        
        var crossings = 0
        for (i in 1 until n) {
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (n - 1)
    }
    
    private fun updateNoiseFloor(rmsDb: Double) {
        rmsHistory.add(rmsDb)
        if (rmsHistory.size > maxHistorySize) {
            rmsHistory.removeAt(0)
        }
        
        if (rmsHistory.size >= 50) { // Wait for enough samples
            val sorted = rmsHistory.sorted()
            val percentile30 = sorted[(sorted.size * 0.3).toInt()]
            
            // Apply exponential smoothing
            noiseFloor = 0.9 * noiseFloor + 0.1 * percentile30
            
            // Clamp to reasonable range
            noiseFloor = noiseFloor.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }
    }
}

/**
 * AudioRecord wrapper for VAD processing
 */
class MicSampler {
    @SuppressLint("MissingPermission")
    fun open(sampleRate: Int, channel: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channel)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channel, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4 * VadEngine.FRAME_SAMPLES * 2) // Buffer for several frames
        
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .build()
    }
}

/**
 * Pre-roll buffer to capture audio before speech detection
 */
class PreRollBuffer(private val seconds: Double) {
    private val maxSamples = (VadEngine.SAMPLE_RATE * seconds).toInt()
    private val buffer = ShortArray(maxSamples)
    private var writeIndex = 0
    private var isFull = false
    
    fun push(frame: ShortArray, n: Int) {
        for (i in 0 until n) {
            buffer[writeIndex] = frame[i]
            writeIndex = (writeIndex + 1) % maxSamples
            if (writeIndex == 0) isFull = true
        }
    }
    
    fun drainTo(writer: AudioWriter) {
        if (!isFull) {
            // Write from beginning to current position
            writer.write(buffer, 0, writeIndex)
        } else {
            // Write from current position to end, then from beginning
            writer.write(buffer, writeIndex, maxSamples - writeIndex)
            writer.write(buffer, 0, writeIndex)
        }
    }
    
    fun clear() {
        writeIndex = 0
        isFull = false
    }
}

/**
 * Interface for writing audio data
 */
interface AudioWriter {
    fun write(frame: ShortArray, offset: Int, length: Int)
    fun close()
}
