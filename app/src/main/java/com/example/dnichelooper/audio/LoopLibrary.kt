package com.example.dnichelooper.audio

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class SavedLoop(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
)

/**
 * Lists loops saved by [LoopSaver] and decodes them back to mono float PCM
 * at the engine sample rate. Decoding runs off the audio path.
 */
object LoopLibrary {

    fun list(context: Context): List<SavedLoop> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listMediaStore(context)
        else listAppMusicDir(context)

    private fun listMediaStore(context: Context): List<SavedLoop> {
        val collection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_MUSIC}/DNicheLooper%")
        val order = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val result = mutableListOf<SavedLoop>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, order)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    result += SavedLoop(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        name = cursor.getString(nameCol),
                        durationMs = cursor.getLong(durCol),
                    )
                }
            }
        return result
    }

    private fun listAppMusicDir(context: Context): List<SavedLoop> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DNicheLooper")
        return dir.listFiles { f -> f.isFile && f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { SavedLoop(Uri.fromFile(it), it.name, 0L) }
            ?: emptyList()
    }

    /**
     * Renames a saved loop and returns its new display name. On Q+ this
     * updates the MediaStore entry, which only works for files this app
     * installation created — loops from a previous install raise a
     * SecurityException that we surface as a readable error.
     */
    fun rename(context: Context, loop: SavedLoop, newName: String): Result<String> = runCatching {
        val fileName = LoopSaver.toFileName(newName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            }
            val updated = try {
                context.contentResolver.update(loop.uri, values, null, null)
            } catch (e: SecurityException) {
                throw IllegalStateException(
                    "Cannot rename \"${loop.name}\" — it was created by a previous installation of this app.",
                    e,
                )
            }
            check(updated > 0) { "MediaStore refused to rename ${loop.name}" }
        } else {
            val source = File(requireNotNull(loop.uri.path) { "Not a file: ${loop.uri}" })
            val target = File(source.parentFile, fileName)
            check(!target.exists()) { "A loop named $fileName already exists" }
            check(source.renameTo(target)) { "Could not rename ${loop.name}" }
        }
        fileName
    }

    /** Decodes to mono float at [targetSampleRate], capped to [maxFrames]. */
    fun decode(
        context: Context,
        loop: SavedLoop,
        targetSampleRate: Int,
        maxFrames: Int,
    ): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, loop.uri, null)
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
            check(trackIndex >= 0 && format != null) { "No audio track in ${loop.name}" }
            extractor.selectTrack(trackIndex)

            val mono = decodeTrackToMono(extractor, format)
            val sourceRate = mono.second
            val samples =
                if (sourceRate == targetSampleRate) mono.first
                else resampleLinear(mono.first, sourceRate, targetSampleRate)
            check(samples.isNotEmpty()) { "Decoded no audio from ${loop.name}" }
            return if (samples.size > maxFrames) samples.copyOf(maxFrames) else samples
        } finally {
            extractor.release()
        }
    }

    /** Returns (mono samples, sample rate). */
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
        var carryIndex = 0  // channel index within the current frame
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
            val a = input[idx.coerceAtMost(input.lastIndex)]
            val b = input[(idx + 1).coerceAtMost(input.lastIndex)]
            output[i] = a + (b - a) * frac
        }
        return output
    }
}
