package com.jnetaol.voicechanger.engine

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class AudioEngine {

    enum class Effect {
        NORMAL, CHIPMUNK, DEEP, ROBOT, ECHO, ALIEN, MEGAPHONE, WHISPER
    }

    data class State(
        val isRunning: Boolean = false,
        val effect: Effect = Effect.NORMAL,
        val pitchShift: Float = 0f,
        val intensity: Float = 0.5f,
        val volume: Float = 0.8f,
        val amplitude: Float = 0f,
        val isRecording: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var processingThread: Thread? = null
    private var rawRecordingSamples = ShortArray(0)
    private var rawRecordingWritePos = 0

    @Volatile
    private var running = false

    @Volatile
    private var saveRecording = false

    private var phase = 0f
    private var echoBuffer = FloatArray(sampleRate / 2)
    private var echoWritePos = 0

    private var robotCounter = 0f
    private var robotRate = 200f

    private fun resetInternalState() {
        phase = 0f
        echoBuffer = FloatArray(sampleRate / 2)
        echoWritePos = 0
        robotCounter = 0f
    }

    fun start() {
        if (running) return
        running = true
        resetInternalState()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        processingThread = Thread {
            processAudio()
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        _state.value = _state.value.copy(isRunning = true)
    }

    fun stop() {
        if (!running) return
        running = false

        processingThread?.join(1000)
        processingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null

        _state.value = _state.value.copy(isRunning = false)
    }

    fun setEffect(effect: Effect) {
        _state.value = _state.value.copy(effect = effect)
        phase = 0f
    }

    fun setPitchShift(value: Float) {
        _state.value = _state.value.copy(pitchShift = value)
    }

    fun setIntensity(value: Float) {
        _state.value = _state.value.copy(intensity = value)
    }

    fun setVolume(value: Float) {
        _state.value = _state.value.copy(volume = value)
    }

    fun startRecordingSamples() {
        rawRecordingSamples = ShortArray(0)
        rawRecordingWritePos = 0
        saveRecording = true
        _state.value = _state.value.copy(isRecording = true)
    }

    fun stopRecordingSamples(): ShortArray {
        saveRecording = false
        _state.value = _state.value.copy(isRecording = false)
        return rawRecordingSamples.copyOfRange(0, rawRecordingWritePos)
    }

    fun clearRecording() {
        rawRecordingSamples = ShortArray(0)
        rawRecordingWritePos = 0
    }

    fun recordingByteArray(): ByteArray {
        val processed = applyEffectToBuffer(rawRecordingSamples.copyOfRange(0, rawRecordingWritePos), _state.value)
        val bytes = ByteArray(processed.size * 2)
        for (i in processed.indices) {
            val sample = processed[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun playRecording(data: ByteArray) {
        Thread {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                data.size,
                AudioTrack.MODE_STATIC
            )
            track.write(data, 0, data.size)
            track.play()
            Thread.sleep((data.size * 1000L / (sampleRate * 2)) + 100)
            track.stop()
            track.release()
        }.start()
    }

    fun recordingWavBytes(): ByteArray {
        val processed = applyEffectToBuffer(rawRecordingSamples.copyOfRange(0, rawRecordingWritePos), _state.value)
        val numSamples = processed.size
        val dataSize = numSamples * 2
        val fileSize = 44 + dataSize

        val buffer = ByteArray(fileSize)
        writeInt(buffer, 0, 0x46464952) // "RIFF"
        writeInt(buffer, 4, fileSize - 8)
        writeInt(buffer, 8, 0x45564157) // "WAVE"
        writeInt(buffer, 12, 0x20746D66) // "fmt "
        writeInt(buffer, 16, 16)
        writeShort(buffer, 20, 1) // PCM
        writeShort(buffer, 22, 1) // mono
        writeInt(buffer, 24, sampleRate)
        writeInt(buffer, 28, sampleRate * 2)
        writeShort(buffer, 32, 2)
        writeShort(buffer, 34, 16)
        writeInt(buffer, 36, 0x61746164) // "data"
        writeInt(buffer, 40, dataSize)

        for (i in 0 until numSamples) {
            val sample = processed[i].toInt()
            buffer[44 + i * 2] = (sample and 0xFF).toByte()
            buffer[44 + i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        return buffer
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun processAudio() {
        val inputBuffer = ShortArray(bufferSize)
        val outputBuffer = ShortArray(bufferSize)

        while (running) {
            val stateSnapshot = _state.value
            val readCount = audioRecord?.read(inputBuffer, 0, bufferSize) ?: break

            if (readCount <= 0) continue

            var maxAmp = 0f

            for (i in 0 until readCount) {
                val rawSample = inputBuffer[i]
                if (saveRecording) {
                    if (rawRecordingWritePos >= rawRecordingSamples.size) {
                        rawRecordingSamples = rawRecordingSamples.copyOf(
                            maxOf(rawRecordingSamples.size * 2, sampleRate * 60)
                        )
                    }
                    rawRecordingSamples[rawRecordingWritePos++] = rawSample
                }

                var sample = rawSample.toFloat()
                val rawAbs = abs(sample)
                if (rawAbs > maxAmp) maxAmp = rawAbs

                sample = applyEffect(sample, stateSnapshot)
                outputBuffer[i] = sample.toInt().toShort()
            }

            if (!stateSnapshot.isRecording) {
                audioTrack?.write(outputBuffer, 0, readCount)
            }

            if (maxAmp > 0) {
                val normalizedAmp = maxAmp / 32767f
                _state.value = _state.value.copy(amplitude = normalizedAmp)
            }
        }
    }

    private fun applyEffect(input: Float, stateSnapshot: State): Float {
        val volume = stateSnapshot.volume
        val intensity = stateSnapshot.intensity
        val pitchShift = stateSnapshot.pitchShift

        var result = when (stateSnapshot.effect) {
            Effect.NORMAL -> {
                input * volume
            }
            Effect.CHIPMUNK -> {
                applyPitchShift(input, pitchShift + 5f + intensity * 3f) * volume
            }
            Effect.DEEP -> {
                applyPitchShift(input, pitchShift - 5f - intensity * 3f) * volume
            }
            Effect.ROBOT -> {
                val mod = sin(2.0 * PI.toFloat() * robotRate / sampleRate * robotCounter).toFloat()
                robotCounter += 1f
                if (robotCounter >= sampleRate) robotCounter = 0f
                val dry = input * (1f - intensity) * volume
                val wet = mod * intensity * 0.5f * volume
                dry + wet
            }
            Effect.ECHO -> {
                processEcho(input, intensity, volume)
            }
            Effect.ALIEN -> {
                val mod = sin(2.0 * PI.toFloat() * (80f + intensity * 120f) / sampleRate * robotCounter).toFloat()
                robotCounter += 1f
                if (robotCounter >= sampleRate) robotCounter = 0f
                val pitched = applyPitchShift(input, 3f + intensity * 4f)
                val echoed = processEcho(pitched, intensity * 0.6f, 1f)
                (echoed * volume + mod * intensity * 0.3f * volume)
            }
            Effect.MEGAPHONE -> {
                val drive = 1f + intensity * 4f
                val saturated = (input * drive).coerceIn(-32767f, 32767f)
                saturated * volume * 0.7f
            }
            Effect.WHISPER -> {
                val noise = ((Math.random() * 2.0 - 1.0) * 32767).toFloat()
                val wet = noise * intensity * volume * 0.5f
                val dry = input * (1f - intensity) * volume * 0.4f
                wet + dry
            }
        }
        return clamp(result)
    }

    private fun applyEffectToBuffer(input: ShortArray, stateSnapshot: State): ShortArray {
        val output = ShortArray(input.size)
        // Temporary reset for processing
        val oldPhase = phase
        val oldEchoBuffer = echoBuffer.copyOf()
        val oldEchoWritePos = echoWritePos
        val oldRobotCounter = robotCounter

        resetInternalState()
        for (i in input.indices) {
            output[i] = applyEffect(input[i].toFloat(), stateSnapshot).toInt().toShort()
        }

        // Restore state for real-time
        phase = oldPhase
        echoBuffer = oldEchoBuffer
        echoWritePos = oldEchoWritePos
        robotCounter = oldRobotCounter

        return output
    }

    private fun applyPitchShift(input: Float, semitones: Float): Float {
        if (abs(semitones) < 0.1f) return input
        val factor = 2.0.pow((semitones / 12).toDouble()).toFloat()
        phase += factor
        if (phase >= sampleRate) phase -= sampleRate
        val mod = sin(2f * PI.toFloat() * (abs(semitones) * 6f + 8f) * phase / sampleRate)
        return input * 0.6f + mod * input * 0.4f * abs(factor - 1f)
    }

    private fun processEcho(input: Float, intensity: Float, volume: Float): Float {
        val bufferLen = echoBuffer.size
        val delayLen = (sampleRate * (0.15f + intensity * 0.4f)).toInt().coerceIn(0, bufferLen - 1)
        val readPos = (echoWritePos - delayLen + bufferLen) % bufferLen

        val delayed = echoBuffer[readPos]
        val feedback = delayed * intensity * 0.7f

        echoBuffer[echoWritePos] = clamp(input + feedback)
        echoWritePos = (echoWritePos + 1) % bufferLen

        return clamp((input + delayed * intensity * 0.5f) * volume)
    }

    private fun clamp(value: Float): Float {
        return value.coerceIn(-32767f, 32767f)
    }
}
