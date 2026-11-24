package com.stickynotes.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Same shape as the desktop app and Firestore: users/{uid}/notes/{id}
data class Item(val id: String = UUID.randomUUID().toString(), val text: String = "", val done: Boolean = false)

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "text",
    val text: String = "",
    val items: List<Item> = emptyList(),
    val color: String = "yellow",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val isChecklist get() = type == "checklist"

    fun title(): String = when {
        isChecklist && items.isNotEmpty() -> "${items.count { it.done }}/${items.size} done"
        isChecklist -> "Checklist"
        else -> ""
    }

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "type" to type,
        "text" to text,
        "items" to items.map { mapOf("id" to it.id, "text" to it.text, "done" to it.done) },
        "color" to color,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "deleted" to deleted,
    )

    fun toJson() = JSONObject(toMap())

    companion object {
        fun fromMap(id: String, m: Map<String, Any?>): Note = Note(
            id = id,
            type = if (m["type"] == "checklist") "checklist" else "text",
            text = m["text"] as? String ?: "",
            items = (m["items"] as? List<*>).orEmpty().mapNotNull { raw ->
                val i = raw as? Map<*, *> ?: return@mapNotNull null
                Item(i["id"]?.toString() ?: UUID.randomUUID().toString(), i["text"]?.toString() ?: "", i["done"] == true)
            },
            color = (m["color"] as? String)?.takeIf { it in NOTE_COLORS } ?: "yellow",
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
            deleted = m["deleted"] == true,
        )

        fun fromJson(o: JSONObject): Note = fromMap(o.getString("id"), jsonToMap(o))

        private fun jsonToMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> unwrap(o.get(k)) }

        private fun unwrap(v: Any?): Any? = when (v) {
            is JSONObject -> jsonToMap(v)
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}

val NOTE_COLORS: Map<String, Long> = linkedMapOf(
    "yellow" to 0xFFFFF59D,
    "pink" to 0xFFF8BBD0,
    "green" to 0xFFC5E1A5,
    "blue" to 0xFFB3E5FC,
    "purple" to 0xFFD1C4E9,
    "orange" to 0xFFFFCC80,
)

/** Inline checkboxes inside text notes. Typing "(*)" turns into BOX; ticking swaps it for TICKED. */
const val BOX = '☐'
const val TICKED = '☑'
const val BOX_SHORTCUT = "(*)"

fun String.isBoxLine() = trimStart().let { it.startsWith(BOX) || it.startsWith(TICKED) }
fun String.isTickedLine() = trimStart().startsWith(TICKED)

/** Replace every "(*)" with "☐ ". Returns the new text and how the cursor position maps. */
fun expandBoxShortcuts(text: String, cursor: Int): Pair<String, Int> {
    var t = text
    var c = cursor
    var i = t.indexOf(BOX_SHORTCUT)
    while (i >= 0) {
        val repl = if (t.getOrNull(i + BOX_SHORTCUT.length) == ' ') "$BOX" else "$BOX "
        t = t.substring(0, i) + repl + t.substring(i + BOX_SHORTCUT.length)
        if (c >= i + BOX_SHORTCUT.length) c += repl.length - BOX_SHORTCUT.length
        else if (c > i) c = i + repl.length
        i = t.indexOf(BOX_SHORTCUT)
    }
    return t to c
}

/** Flip the checkbox on the given line of a text note. */
fun toggleBoxLine(text: String, line: Int): String {
    val lines = text.split('\n').toMutableList()
    val l = lines.getOrNull(line) ?: return text
    val i = l.indexOfFirst { it == BOX || it == TICKED }
    if (i < 0) return text
    lines[line] = l.substring(0, i) + (if (l[i] == BOX) TICKED else BOX) + l.substring(i + 1)
    return lines.joinToString("\n")
}

const val INK = 0xFF3B3320
const val INK_MUTED = 0x803B3320
