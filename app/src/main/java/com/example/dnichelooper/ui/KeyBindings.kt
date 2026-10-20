package com.example.dnichelooper.ui

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.File

/**
 * Every action in DNicheLooper that can be triggered from a USB/Bluetooth keyboard
 * or foot switch. Each action has a default key, and all bindings can be customized and saved.
 */
enum class KeyAction(
    val id: String,
    val title: String,
    val description: String,
    val defaultKey: Key?,
) {
    REC_STOP("rec_stop", "Stop / Record", "Startet Aufnahme oder stoppt Wiedergabe / Aufnahme", Key.Spacebar),
    RECORD("record", "Record / Set Loop / Re-Rec", "Wie Klick auf den roten Record-Button", Key.R),
    STOP("stop", "Stop", "Stoppt Wiedergabe, Aufnahme oder Count-in", Key.X),
    PLAY_STOP("play_stop", "Play / Stop", "Wechselt zwischen Play und Stop", Key.P),
    OVERDUB("overdub", "Overdub", "Schaltet Overdub an oder aus", Key.O),
    CLEAR("clear", "Clear", "Löscht den aktuellen Loop", Key.C),
    SAVE_LOOP("save_loop", "Loop speichern", "Speichert den Loop als Datei", Key.U),
    CHAIN_1("chain_1", "Amp Slot A", "Wechselt live auf Amp-Slot A", Key.A),
    CHAIN_2("chain_2", "Amp Slot B", "Wechselt live auf Amp-Slot B", Key.S),
    CHAIN_3("chain_3", "Amp Slot C", "Wechselt live auf Amp-Slot C", Key.D),
    DRUMS_MUTE("drums_mute", "Drums Mute", "Live Drop-out der Drums im Takt", Key.M),
    DRUMS_TOGGLE("drums_toggle", "Drums an/aus", "Schaltet die Drum-Begleitung an oder aus", Key.B),
    METRONOME_TOGGLE("metronome_toggle", "Metronom an/aus", "Schaltet den Klick an oder aus", Key.K),
    COUNT_IN_TOGGLE("count_in_toggle", "Count-in an/aus", "Schaltet den 2-Takt-Vorzähler an oder aus", Key.I),
    AUTO_LOOP_TOGGLE("auto_loop_toggle", "Auto-Loop an/aus", "Schaltet die feste Taktanzahl an oder aus", Key.L),
    MONITOR_TOGGLE("monitor_toggle", "Monitor Input an/aus", "Schaltet das direkte Mithören an oder aus", Key.V),
    BPM_DOWN("bpm_down", "BPM −1", "Verringert das Tempo um 1 BPM", Key.DirectionDown),
    BPM_UP("bpm_up", "BPM +1", "Erhöht das Tempo um 1 BPM", Key.DirectionUp),
}

/**
 * Immutable mapping from [KeyAction] to [Key] (or null when unbound).
 */
data class KeyBindings(
    val bindings: Map<KeyAction, Key?> = defaultMap(),
) {
    fun keyFor(action: KeyAction): Key? =
        if (action in bindings) bindings[action] else action.defaultKey

    fun actionFor(key: Key): KeyAction? =
        KeyAction.entries.firstOrNull { keyFor(it) == key }

    /** Full German label for the settings dialog (e.g. "Leertaste", "A", "—"). */
    fun labelFor(action: KeyAction): String =
        keyFor(action)?.let(KeyNames::displayName) ?: "—"

    /** Compact label for buttons/chips (e.g. "Space", "A", null when unbound). */
    fun shortLabelFor(action: KeyAction): String? =
        keyFor(action)?.let(KeyNames::shortName)

    val isDefault: Boolean
        get() = KeyAction.entries.all { keyFor(it) == it.defaultKey }

    /**
     * Assigns [newKey] to [action]. If another action already uses [newKey], the two
     * actions swap their keys so bindings always stay conflict-free.
     */
    fun withKey(action: KeyAction, newKey: Key?): KeyBindings {
        val previousKey = keyFor(action)
        val updated = bindings.toMutableMap()
        if (newKey != null) {
            val conflicting = KeyAction.entries.firstOrNull { it != action && keyFor(it) == newKey }
            if (conflicting != null) {
                updated[conflicting] = previousKey
            }
        }
        updated[action] = newKey
        return KeyBindings(updated)
    }

    fun serialize(): String = buildString {
        appendLine("# DNicheLooper key bindings")
        for (action in KeyAction.entries) {
            val token = keyFor(action)?.let(KeyNames::toToken) ?: NONE_TOKEN
            appendLine("${action.id}=$token")
        }
    }

    companion object {
        private const val NONE_TOKEN = "NONE"

        fun keyLabel(key: Key): String = KeyNames.displayName(key)

        fun defaultMap(): Map<KeyAction, Key?> =
            KeyAction.entries.associateWith { it.defaultKey }

        fun parse(text: String?): KeyBindings {
            if (text.isNullOrBlank()) return KeyBindings()
            val map = defaultMap().toMutableMap()
            val usedKeys = mutableSetOf<Key>()
            val parsedActions = mutableSetOf<KeyAction>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val id = line.substring(0, eq).trim()
                val token = line.substring(eq + 1).trim()
                val action = KeyAction.entries.firstOrNull { it.id == id } ?: continue
                if (token.equals(NONE_TOKEN, ignoreCase = true) || token.isEmpty()) {
                    map[action] = null
                    parsedActions += action
                } else {
                    val key = KeyNames.fromToken(token)
                    if (key != null && usedKeys.add(key)) {
                        map[action] = key
                        parsedActions += action
                    }
                }
            }
            for (action in KeyAction.entries) {
                if (action !in parsedActions) {
                    val def = action.defaultKey
                    map[action] = if (def != null && usedKeys.add(def)) def else null
                }
            }
            return KeyBindings(map)
        }
    }
}

/**
 * Persistence for [KeyBindings] in app-private storage.
 */
object KeyBindingsStore {
    private fun file(context: Context): File = File(context.filesDir, "keys.properties")

    fun load(context: Context): KeyBindings =
        runCatching {
            val f = file(context)
            KeyBindings.parse(f.takeIf { it.isFile }?.readText())
        }.getOrDefault(KeyBindings())

    fun save(context: Context, bindings: KeyBindings): Boolean = runCatching {
        file(context).writeText(bindings.serialize())
        true
    }.getOrDefault(false)
}

/**
 * Human-readable names and serialization tokens for [Key].
 */
object KeyNames {
    private data class Entry(val key: Key, val token: String, val display: String, val short: String = display)

    private val entries: List<Entry> = buildList {
        add(Entry(Key.Spacebar, "Space", "Leertaste", "Space"))
        add(Entry(Key.Enter, "Enter", "Enter", "Enter"))
        add(Entry(Key.NumPadEnter, "NumPadEnter", "Num Enter", "NumEnter"))
        add(Entry(Key.Tab, "Tab", "Tab", "Tab"))
        add(Entry(Key.DirectionUp, "Up", "↑", "↑"))
        add(Entry(Key.DirectionDown, "Down", "↓", "↓"))
        add(Entry(Key.DirectionLeft, "Left", "←", "←"))
        add(Entry(Key.DirectionRight, "Right", "→", "→"))
        add(Entry(Key.PageUp, "PageUp", "Bild ↑", "PgUp"))
        add(Entry(Key.PageDown, "PageDown", "Bild ↓", "PgDn"))
        add(Entry(Key.MoveHome, "Home", "Pos1", "Home"))
        add(Entry(Key.MoveEnd, "End", "Ende", "End"))
        add(Entry(Key.Insert, "Insert", "Einfg", "Ins"))
        add(Entry(Key.Minus, "Minus", "-", "-"))
        add(Entry(Key.Equals, "Equals", "=", "="))
        add(Entry(Key.Plus, "Plus", "+", "+"))
        add(Entry(Key.Comma, "Comma", ",", ","))
        add(Entry(Key.Period, "Period", ".", "."))
        add(Entry(Key.Slash, "Slash", "/", "/"))
        add(Entry(Key.Semicolon, "Semicolon", ";", ";"))
        add(Entry(Key.Apostrophe, "Apostrophe", "'", "'"))
        add(Entry(Key.LeftBracket, "LeftBracket", "[", "["))
        add(Entry(Key.RightBracket, "RightBracket", "]", "]"))
        add(Entry(Key.Backslash, "Backslash", "\\", "\\"))
        add(Entry(Key.Grave, "Grave", "`", "`"))

        val letters = listOf(
            Key.A to "A", Key.B to "B", Key.C to "C", Key.D to "D", Key.E to "E",
            Key.F to "F", Key.G to "G", Key.H to "H", Key.I to "I", Key.J to "J",
            Key.K to "K", Key.L to "L", Key.M to "M", Key.N to "N", Key.O to "O",
            Key.P to "P", Key.Q to "Q", Key.R to "R", Key.S to "S", Key.T to "T",
            Key.U to "U", Key.V to "V", Key.W to "W", Key.X to "X", Key.Y to "Y",
            Key.Z to "Z",
        )
        for ((k, name) in letters) add(Entry(k, name, name, name))

        val digits = listOf(
            Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
            Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
        )
        for ((k, name) in digits) add(Entry(k, name, name, name))

        val numpad = listOf(
            Key.NumPad0 to "0", Key.NumPad1 to "1", Key.NumPad2 to "2", Key.NumPad3 to "3",
            Key.NumPad4 to "4", Key.NumPad5 to "5", Key.NumPad6 to "6", Key.NumPad7 to "7",
            Key.NumPad8 to "8", Key.NumPad9 to "9",
        )
        for ((k, d) in numpad) add(Entry(k, "NumPad$d", "Num $d", "Num$d"))
        add(Entry(Key.NumPadAdd, "NumPadAdd", "Num +", "Num+"))
        add(Entry(Key.NumPadSubtract, "NumPadSubtract", "Num −", "Num−"))
        add(Entry(Key.NumPadMultiply, "NumPadMultiply", "Num *", "Num*"))
        add(Entry(Key.NumPadDivide, "NumPadDivide", "Num /", "Num/"))

        val fKeys = listOf(
            Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4",
            Key.F5 to "F5", Key.F6 to "F6", Key.F7 to "F7", Key.F8 to "F8",
            Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12",
        )
        for ((k, name) in fKeys) add(Entry(k, name, name, name))
    }

    private val byKey: Map<Key, Entry> = entries.associateBy { it.key }
    private val byToken: Map<String, Entry> = entries.associateBy { it.token.lowercase() }

    private val modifierKeys = setOf(
        Key.ShiftLeft, Key.ShiftRight,
        Key.CtrlLeft, Key.CtrlRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.CapsLock, Key.NumLock, Key.ScrollLock,
    )

    fun isModifier(key: Key): Boolean = key in modifierKeys

    fun displayName(key: Key): String =
        byKey[key]?.display ?: "Key(${key.keyCode shr 32})"

    fun shortName(key: Key): String =
        byKey[key]?.short ?: displayName(key)

    fun toToken(key: Key): String =
        byKey[key]?.token ?: "code:${key.keyCode}"

    fun fromToken(token: String): Key? {
        byToken[token.lowercase()]?.let { return it.key }
        if (token.startsWith("code:")) {
            return token.removePrefix("code:").toLongOrNull()?.let(::Key)
        }
        return null
    }
}

/**
 * Handles key events for the main window with auto-repeat suppression,
 * button-activation swallowing (so Spacebar never clicks a focused UI button),
 * and automatic bypass when a text field is focused.
 */
class ShortcutDispatcher(private val viewModel: TransportViewModel) {
    private val heldKeys = mutableSetOf<Key>()

    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        val state = viewModel.uiState.value

        if (state.textFieldFocused) {
            if (event.type == KeyEventType.KeyUp) {
                heldKeys.remove(event.key)
            }
            return false
        }

        val target = state.capturingKeyFor
        if (target != null) {
            if (KeyNames.isModifier(event.key)) return false
            if (event.type == KeyEventType.KeyUp) {
                heldKeys.remove(event.key)
                return true
            }
            if (event.type == KeyEventType.KeyDown) {
                heldKeys.add(event.key)
                when (event.key) {
                    Key.Escape -> viewModel.setCapturingKeyFor(null)
                    Key.Backspace, Key.Delete -> viewModel.assignKey(target, null)
                    else -> viewModel.assignKey(target, event.key)
                }
                return true
            }
            return false
        }

        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            return false
        }

        val action = state.keyBindings.actionFor(event.key) ?: return false

        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (heldKeys.add(event.key)) {
                    viewModel.performKeyAction(action)
                }
                true
            }
            KeyEventType.KeyUp -> {
                heldKeys.remove(event.key)
                true
            }
            else -> false
        }
    }
}
