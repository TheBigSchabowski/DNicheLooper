package com.example.dnichelooper.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer

/**
 * Writes mono float samples to a 24-bit PCM WAV (RIFF) — uncompressed, so it is
 * suitable for A/B comparison against a desktop plugin render of the same
 * signal. Writing is streaming (no full-buffer copy), so long captures fit.
 */
object WavWriter {

    /**
     * Writes [samples] (mono float, -1..1) to [file] as a 24-bit signed-LE PCM
     * WAV at [sampleRate]. Overwrites an existing file.
     */
    fun writeMono(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 3
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)                 // PCM chunk
            header.putShort(1)                // audioFormat = PCM
            header.putShort(1)                // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 3)     // byte rate
            header.putShort(3)                // block align (1ch * 3 bytes)
            header.putShort(24)               // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            raf.write(header.array())

            // 24-bit signed little-endian, clamped to [-1,1].
            val buf = ByteArray(3 * 8192)
            var p = 0
            while (p < samples.size) {
                val n = minOf(8192, samples.size - p)
                for (i in 0 until n) {
                    val v = samples[p + i]
                    val clamped = if (!v.isFinite()) 0f else v.coerceIn(-1f, 1f)
                    val iv = (clamped * 8388607f).toInt()
                    val off = i * 3
                    buf[off] = (iv and 0xFF).toByte()
                    buf[off + 1] = ((iv shr 8) and 0xFF).toByte()
                    buf[off + 2] = ((iv shr 16) and 0xFF).toByte()
                }
                raf.write(buf, 0, n * 3)
                p += n
            }
        } finally {
            raf.close()
        }
    }
}
