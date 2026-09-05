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
    @Volatile var onChunk: ((ByteArray) -> Unit)? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>(60)
    @Volatile private var capturing = false
    @Volatile private var playing = false

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
                    
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)) / 32768.0
                        sum += v * v
                    }
                    Bus.vu.value = sqrt(sum / (read / 2)).toFloat()
                    
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
        Bus.vu.value = 0f
    }

    fun startPlayback() {
        if (playing) return
        playing = true
        thread(name = "audio-play") {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("DEPRECATION")
            val tr = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 8), AudioTrack.MODE_STREAM)
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
}
