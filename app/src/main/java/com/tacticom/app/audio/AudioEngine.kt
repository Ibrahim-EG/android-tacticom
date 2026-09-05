package com.tacticom.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.tacticom.app.Bus
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.concurrent.thread

class AudioEngine(private val ctx: Context) {
    companion object {
        const val RATE = 48000
        const val CHUNK = RATE * 10 / 1000 * 2
    }

    @Volatile var transmitting = false
    @Volatile var earpiece = false
    @Volatile var onChunk: ((ByteArray) -> Unit)? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>(60)
    @Volatile private var capturing = false
    @Volatile private var playing = false

    private val audioManager get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Only start capturing when PTT is pressed
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (capturing) return
        capturing = true
        thread(name = "audio-capture") {
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4)
                )
                record = rec
                rec.startRecording()
                val buf = ByteArray(CHUNK)
                while (capturing) {
                    val read = rec.read(buf, 0, CHUNK)
                    if (read <= 0) continue
                    
                    // Calculate VU meter
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)) / 32768.0
                        sum += v * v
                    }
                    Bus.vu.value = sqrt(sum / (read / 2)).toFloat()
                    
                    // Only send audio when actually transmitting
                    if (transmitting) onChunk?.invoke(buf.copyOf(read))
                }
                runCatching { rec.stop(); rec.release() }
                record = null
            } catch (_: Exception) { 
                Bus.toastMsg.value = "Mic error" 
            }
        }
    }

    fun stopCapture() { 
        capturing = false 
        Bus.vu.value = 0f // Reset VU meter when not capturing
    }

    // Playback runs continuously to receive audio
    fun startPlayback() {
        if (playing) return
        playing = true
        applyRouting()
        thread(name = "audio-play") {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val stream = if (earpiece) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            @Suppress("DEPRECATION")
            val tr = AudioTrack(stream, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 8), AudioTrack.MODE_STREAM)
            track = tr
            tr.play()
            while (playing) {
                val chunk = queue.poll(15, TimeUnit.MILLISECONDS) ?: continue
                tr.write(chunk, 0, chunk.size)
            }
            runCatching { tr.stop(); tr.release() }
            track = null
        }
    }

    fun stopPlayback() { 
        playing = false
        queue.clear()
    }

    fun feed(data: ByteArray) {
        if (queue.remainingCapacity() == 0) queue.poll()
        queue.offer(data)
    }

    fun applyRouting() {
        if (earpiece) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }
}
