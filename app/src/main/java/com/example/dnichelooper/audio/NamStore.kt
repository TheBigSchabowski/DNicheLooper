package com.example.dnichelooper.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One named snapshot of all three amp slots (model file names + active slot) and the cab IR (Slot D). */
data class NamPreset(
    val name: String,
    val slotFileNames: List<String?>,
    val activeSlot: Int,
    val ir: String? = null,  // Slot D cab-IR file name, or null = bypass
)

/**
 * App-private storage for NAM models and chain presets.
 *
 * Models picked via the system file picker are copied into
 * files/nam-models so presets keep working after the original file moves.
 * Presets are one small JSON file each in files/nam-presets — nothing is
 * auto-saved, presets exist only after the user explicitly saves one
 * (same rule as the desktop NicheLooper).
 */
object NamStore {

    const val NUM_SLOTS = 3

    private fun modelsDir(context: Context) = File(context.filesDir, "nam-models")
    private fun presetsDir(context: Context) = File(context.filesDir, "nam-presets")

    /**
     * Copies the picked .nam file into app storage and returns it. A model
     * with the same display name is overwritten (same model, newer file).
     */
    fun importModel(context: Context, uri: Uri): File {
        val dir = modelsDir(context)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create ${dir.absolutePath}" }

        var name = displayName(context, uri) ?: "model.nam"
        name = sanitize(name.removeSuffix(".nam")) + ".nam"

        val target = File(dir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Cannot read $uri" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        check(target.length() > 0) { "Model file is empty" }
        return target
    }

    fun modelFile(context: Context, fileName: String): File = File(modelsDir(context), fileName)

    // --- Presets --------------------------------------------------------

    fun listPresets(context: Context): List<String> {
        val dir = presetsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()
    }

    fun savePreset(
        context: Context,
        name: String,
        slotFileNames: List<String?>,
        activeSlot: Int,
        ir: String?,
    ) {
        val dir = presetsDir(context)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create ${dir.absolutePath}" }
        val json = JSONObject().apply {
            put("slots", JSONArray(slotFileNames.map { it ?: JSONObject.NULL }))
            put("active", activeSlot)
            // Slot D cab IR (file name | null). Older presets without this
            // field load as null (= no IR); keep it optional on read.
            put("ir", ir ?: JSONObject.NULL)
        }
        presetFile(context, name).writeText(json.toString())
    }

    fun loadPreset(context: Context, name: String): NamPreset {
        val file = presetFile(context, name)
        check(file.isFile) { "Preset \"$name\" not found" }
        val json = JSONObject(file.readText())
        val slotsJson = json.getJSONArray("slots")
        val slots = (0 until NUM_SLOTS).map { i ->
            if (i < slotsJson.length() && !slotsJson.isNull(i)) slotsJson.getString(i) else null
        }
        // "ir" is optional: presets saved before Slot D existed have no such
        // field and load as null (= bypass), as do presets with an explicit null.
        val ir = if (json.has("ir") && !json.isNull("ir")) json.getString("ir") else null
        return NamPreset(
            name = name,
            slotFileNames = slots,
            activeSlot = json.optInt("active", 0).coerceIn(0, NUM_SLOTS - 1),
            ir = ir,
        )
    }

    fun deletePreset(context: Context, name: String): Boolean = presetFile(context, name).delete()

    private fun presetFile(context: Context, name: String) =
        File(presetsDir(context), sanitize(name) + ".json")

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
        return cleaned.ifBlank { "preset" }
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
