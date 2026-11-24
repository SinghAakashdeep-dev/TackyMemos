package stickynotes

import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.swing.Timer

data class Item(val id: String = UUID.randomUUID().toString(), val text: String = "", val done: Boolean = false)

/** Same shape as Firestore users/{uid}/notes/{id} and the Android app. */
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "text",
    val text: String = "",
    val items: List<Item> = emptyList(),
    val color: String = "yellow",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val isChecklist get() = type == "checklist"

    fun normalized() = copy(
        type = if (type == "checklist") "checklist" else "text",
        color = if (color in NOTE_COLORS) color else "yellow",
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("text", text)
        .put("items", JSONArray(items.map { JSONObject().put("id", it.id).put("text", it.text).put("done", it.done) }))
        .put("color", color).put("createdAt", createdAt).put("updatedAt", updatedAt).put("deleted", deleted)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            type = o.optString("type", "text"),
            text = o.optString("text", ""),
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Item(it.optString("id", UUID.randomUUID().toString()), it.optString("text", ""), it.optBoolean("done"))
                }
            } ?: emptyList(),
            color = o.optString("color", "yellow"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            deleted = o.optBoolean("deleted"),
        ).normalized()
    }
}

val NOTE_COLORS: Map<String, Color> = linkedMapOf(
    "yellow" to Color(0xFFF59D),
    "pink" to Color(0xF8BBD0),
    "green" to Color(0xC5E1A5),
    "blue" to Color(0xB3E5FC),
    "purple" to Color(0xD1C4E9),
    "orange" to Color(0xFFCC80),
)

data class Bounds(val x: Int, val y: Int, val w: Int, val h: Int)

fun now() = System.currentTimeMillis()

/** All note state. Only touched on the Swing event thread. */
class Store(val dir: File) {
    val notes = LinkedHashMap<String, Note>()
    val layout = HashMap<String, Bounds>()
    /** Notes changed locally and not yet confirmed by the server. */
    val dirty = LinkedHashSet<String>()
    var alwaysOnTop = false
    var launchAtLogin = false
    /** Highest server syncedAt seen; the next poll asks only for newer changes. */
    var syncCursor: String? = null
    var onLocalChange: (() -> Unit)? = null

    private val file = File(dir, "notes.json")
    private val timer = Timer(300) { saveNow() }.apply { isRepeats = false }

    init {
        dir.mkdirs()
        if (file.exists()) {
            try {
                val o = JSONObject(file.readText())
                o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) Note.fromJson(a.getJSONObject(i)).let { notes[it.id] = it } }
                o.optJSONObject("layout")?.let { l ->
                    for (k in l.keys()) l.getJSONObject(k).let { b -> layout[k] = Bounds(b.getInt("x"), b.getInt("y"), b.getInt("w"), b.getInt("h")) }
                }
                o.optJSONArray("dirty")?.let { a -> for (i in 0 until a.length()) dirty += a.getString(i) }
                alwaysOnTop = o.optBoolean("alwaysOnTop")
                launchAtLogin = o.optBoolean("launchAtLogin")
                syncCursor = o.optString("syncCursor").ifEmpty { null }
            } catch (e: Exception) {
                // Keep the unreadable file for recovery rather than overwriting it.
                file.copyTo(File(dir, "notes.json.corrupt-${now()}"), overwrite = true)
                notes.clear()
            }
        }
    }

    fun live() = notes.values.filter { !it.deleted }
    fun get(id: String) = notes[id]

    fun save() = timer.restart()

    fun saveNow() {
        timer.stop()
        val o = JSONObject()
            .put("notes", JSONArray(notes.values.map { it.toJson() }))
            .put("layout", JSONObject().also { l -> layout.forEach { (k, b) -> l.put(k, JSONObject().put("x", b.x).put("y", b.y).put("w", b.w).put("h", b.h)) } })
            .put("dirty", JSONArray(dirty.toList()))
            .put("alwaysOnTop", alwaysOnTop)
            .put("launchAtLogin", launchAtLogin)
            .put("syncCursor", syncCursor ?: "")
        val tmp = File(dir, "notes.json.tmp")
        tmp.writeText(o.toString(2))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun create(type: String, color: String): Note {
        val t = now()
        val n = Note(type = type, color = color, createdAt = t, updatedAt = t).normalized()
        notes[n.id] = n
        markDirty(n.id)
        return n
    }

    /** Local edit. Bumps updatedAt so it wins last-writer-wins merges. */
    fun update(id: String, change: (Note) -> Note): Note? {
        val old = notes[id] ?: return null
        val n = change(old).copy(id = id, updatedAt = maxOf(now(), old.updatedAt + 1)).normalized()
        notes[id] = n
        markDirty(id)
        return n
    }

    fun remove(id: String): Note? {
        layout.remove(id)
        return update(id) { it.copy(deleted = true, text = "", items = emptyList()) }
    }

    private fun markDirty(id: String) {
        dirty += id
        save()
        onLocalChange?.invoke()
    }

    /** Apply a note from the server if it is strictly newer. */
    fun applyRemote(r: Note): Boolean {
        val local = notes[r.id]
        if (local != null && local.updatedAt >= r.updatedAt) return false
        notes[r.id] = r.normalized()
        dirty -= r.id
        if (r.deleted) layout.remove(r.id)
        save()
        return true
    }

    fun setLayout(id: String, b: Bounds) {
        layout[id] = b
        save()
    }
}
