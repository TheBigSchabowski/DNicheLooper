package com.example.dnichelooper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * App-private storage and decoding for cabinet impulse responses (the fixed
 * "Slot D" that follows the NAM amp).
 *
 * Picked WAV/Audio files are copied into files/ir so they survive a source
 * move and can be re-decoded on every engine (re)start. Decoding to mono
 * float at the *current* engine sample rate happens fresh each time the
 * engine starts — only the original file is kept, never a rendered array,
 * because the engine rate can change between USB reconnects.
 */
object IrStore {

    private fun irDir(context: Context) = File(context.filesDir, "ir")

    /**
     * Copies the picked audio file into app storage and returns it. A file
     * with the same display name is overwritten.
     */
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
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.fromFile(file), null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
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

    /** Returns (mono samples, sample rate). Mirrors LoopLibrary.decodeTrackToMono. */
    private fun decodeTrackToMono(
        extractor: MediaExtractor,
        format: MediaFormat,
    ): Pair<FloatArray, Int> {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        val chunks = mutableListOf<ShortArray>()
        var totalShorts = 0
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buf.asShortBuffer().get(shorts)
                            chunks += shorts
                            totalShorts += shorts.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }

        val frames = totalShorts / channels
        val mono = FloatArray(frames)
        var frame = 0
        var carryIndex = 0
        var accum = 0f
        for (chunk in chunks) {
            for (s in chunk) {
                accum += s / 32768f
                if (++carryIndex == channels) {
                    mono[frame++] = accum / channels
                    accum = 0f
                    carryIndex = 0
                }
            }
        }
        return mono to sampleRate
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        if (outLength <= 0) return FloatArray(0)
        val output = FloatArray(outLength)
        val step = fromRate.toDouble() / toRate
        for (i in output.indices) {
            val pos = i * step
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
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
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
