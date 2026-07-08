package com.example.dnichelooper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-private storage and decoding for cabinet impulse responses (the fixed
 * "Slot D" that follows the NAM amp).
 *
 * Picked WAV/audio files are copied into files/ir so they survive a source
 * move and can be re-decoded on every engine (re)start. Decoding to mono
 * float at the *current* engine sample rate happens fresh each time the
 * engine starts — only the original file is kept, never a rendered array,
 * because the engine rate can change between USB reconnects.
 *
 * WAV is parsed directly (RIFF chunks) so all common IR encodings work
 * correctly — including 32-bit float and 24-bit int, which the MediaCodec
 * raw path misreads as 16-bit. Non-WAV audio falls back to MediaCodec.
 */
object IrStore {

    private fun irDir(context: Context) = File(context.filesDir, "ir")

    /** Copies the picked audio file into app storage and returns it. */
    fun importIr(context: Context, uri: Uri): File {
        val dir = irDir(context)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create ${dir.absolutePath}" }

        var name = displayName(context, uri) ?: "ir.wav"
        if (!name.contains('.')) name = "$name.wav"
        name = sanitize(name)

        val target = File(dir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Cannot read $uri" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        check(target.length() > 0) { "IR file is empty" }
        return target
    }

    fun irFile(context: Context, fileName: String): File = File(irDir(context), fileName)

    /**
     * Decodes [file] to mono float PCM at [targetSampleRate]. The native
     * side truncates to IrProcessor::kMaxTaps (4096) and normalizes, so no
     * cap is applied here.
     */
    fun decode(context: Context, file: File, targetSampleRate: Int): FloatArray {
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return FloatArray(0)
        // Direct WAV parse when possible (handles float/24-bit correctly);
        // otherwise fall back to the MediaCodec path.
        return if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) {
            decodeWav(bytes, targetSampleRate)
        } else {
            decodeViaMediaCodec(context, file, targetSampleRate)
        }
    }

    /** Direct RIFF/WAVE parser — 8/16/24/32-bit int and 32/64-bit float. */
    private fun decodeWav(bytes: ByteArray, targetSampleRate: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var audioFormat = 1
        var channels = 1
        var sampleRate = 48000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLen = 0

        var i = 12
        while (i + 8 <= bytes.size) {
            val cid = String(bytes, i, 4, Charsets.US_ASCII)
            val clen = buf.getInt(i + 4)
            val body = i + 8
            when (cid) {
                "fmt " -> if (clen >= 16 && body + 16 <= bytes.size) {
                    audioFormat = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = body
                    dataLen = clen
                }
            }
            i = body + clen + (clen and 1)
            if (cid == "data") break
        }
        if (dataOffset < 0 || channels < 1) return FloatArray(0)

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return FloatArray(0)
        val frameBytes = bytesPerSample * channels
        // Clamp the declared data length to what's actually present.
        val available = bytes.size - dataOffset
        val len = dataLen.coerceAtMost(available)
        val numFrames = len / frameBytes
        if (numFrames <= 0) return FloatArray(0)

        val mono = FloatArray(numFrames)
        buf.position(dataOffset)
        val isFloat = audioFormat == 3
        for (f in 0 until numFrames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += readSample(buf, audioFormat, bitsPerSample, isFloat)
            }
            // average channels (IRs are usually mono; averaging keeps a
            // stereo IR's level sane — the native loader normalizes anyway)
            mono[f] = sum / channels
        }

        return if (sampleRate == targetSampleRate) mono
        else resampleLinear(mono, sampleRate, targetSampleRate)
    }

    private fun readSample(
        buf: ByteBuffer, audioFormat: Int, bits: Int, isFloat: Boolean,
    ): Float {
        if (isFloat) {
            return when (bits) {
                32 -> buf.getFloat()
                64 -> buf.getDouble().toFloat()
                else -> { buf.position(buf.position() + (bits / 8)); 0f }
            }
        }
        // integer PCM
        return when (bits) {
            8 -> (buf.get().toInt() and 0xFF).let { (it - 128) / 128f } // 8-bit WAV is unsigned
            16 -> buf.getShort().toInt() / 32768f
            24 -> {
                val b0 = buf.get().toInt() and 0xFF
                val b1 = buf.get().toInt() and 0xFF
                val b2 = buf.get().toInt() and 0xFF
                var v = b0 or (b1 shl 8) or (b2 shl 16)
                if ((b2 and 0x80) != 0) v -= (1 shl 24)
                v / 8388608f
            }
            32 -> buf.getInt() / 2147483648f
            else -> { buf.position(buf.position() + (bits / 8)); 0f }
        }
    }

    /** MediaCodec fallback for non-WAV audio (mirrors LoopLibrary.decode). */
    private fun decodeViaMediaCodec(context: Context, file: File, targetSampleRate: Int): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.fromFile(file), null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            check(trackIndex >= 0 && format != null) { "No audio track in ${file.name}" }
            extractor.selectTrack(trackIndex)
            val mono = decodeTrackToMono(extractor, format)
            val sourceRate = mono.second
            if (mono.first.isEmpty()) return FloatArray(0)
            return if (sourceRate == targetSampleRate) mono.first
            else resampleLinear(mono.first, sourceRate, targetSampleRate)
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrackToMono(
        extractor: MediaExtractor, format: MediaFormat,
    ): Pair<FloatArray, Int> {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        val chunks = mutableListOf<ShortArray>()
        var totalShorts = 0
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        try {
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val b = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(b, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT); sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val b = codec.getOutputBuffer(outIndex)!!; b.position(info.offset); b.limit(info.offset + info.size)
                            val s = ShortArray(info.size / 2); b.asShortBuffer().get(s); chunks += s; totalShorts += s.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop()
        } finally { codec.release() }
        val frames = totalShorts / channels
        val mono = FloatArray(frames)
        var frame = 0; var carry = 0; var acc = 0f
        for (chunk in chunks) for (s in chunk) {
            acc += s / 32768f
            if (++carry == channels) { mono[frame++] = acc / channels; acc = 0f; carry = 0 }
        }
        return mono to sampleRate
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        if (outLength <= 0) return FloatArray(0)
        val output = FloatArray(outLength)
        val step = fromRate.toDouble() / toRate
        for (i in output.indices) {
            val pos = i * step; val idx = pos.toInt(); val frac = (pos - idx).toFloat()
            val a = input[idx.coerceIn(0, input.lastIndex)]
            val b = input[(idx + 1).coerceIn(0, input.lastIndex)]
            output[i] = a + (b - a) * frac
        }
        return output
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120)
        return cleaned.ifBlank { "ir.wav" }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
