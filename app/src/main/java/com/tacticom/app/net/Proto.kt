package com.tacticom.app.net

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Tiny binary framing over TCP: [4-byte length][1-byte type][payload].
 * Type 0 = JSON control message, Type 1 = raw PCM audio.
 */
object Proto {
    const val TYPE_JSON: Byte = 0
    const val TYPE_PCM: Byte = 1

    fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(5).putInt(payload.size + 1).put(type).array()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    fun readFrame(ins: InputStream): Pair<Byte, ByteArray>? {
        val lenBuf = readExact(ins, 4) ?: return null
        val len = ByteBuffer.wrap(lenBuf).int
        if (len <= 0 || len > 2_000_000) return null
        val body = readExact(ins, len) ?: return null
        return body[0] to body.copyOfRange(1, body.size)
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    fun json(vararg pairs: Pair<String, Any?>): ByteArray =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString().toByteArray()

    fun parse(bytes: ByteArray): JSONObject = JSONObject(String(bytes))
}
