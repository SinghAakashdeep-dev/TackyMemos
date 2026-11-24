package com.stickynotes.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stickynotes.app.widget.NoteWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.io.File

/**
 * Single source of truth on the phone. Notes are cached in a local file so widgets render
 * instantly and offline; Firestore is mirrored with last-writer-wins on `updatedAt`.
 */
class NoteRepo private constructor(private val ctx: Context) {
    private val file = AtomicFile(File(ctx.filesDir, "notes.json"))
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<Map<String, Note>> = _notes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listener: ListenerRegistration? = null

    val user get() = if (StickyApp.firebaseReady) FirebaseAuth.getInstance().currentUser else null

    private fun col(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("notes")

    fun live(): List<Note> = _notes.value.values.filter { !it.deleted }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun create(type: String = "text", color: String = "yellow"): Note {
        val n = Note(type = type, color = color)
        put(n)
        push(n)
        return n
    }

    @Synchronized
    fun update(id: String, change: (Note) -> Note): Note? {
        val old = _notes.value[id] ?: return null
        val n = change(old).copy(id = id, updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1))
        put(n)
        push(n)
        return n
    }

    fun delete(id: String) = update(id) { it.copy(deleted = true, text = "", items = emptyList()) }

    /** Apply remote notes that are strictly newer. On the first snapshot, upload anything we have that's newer. */
    @Synchronized
    fun applyRemote(remote: List<Note>, initial: Boolean) {
        val cur = _notes.value.toMutableMap()
        var changed = false
        for (r in remote) {
            val local = cur[r.id]
            if (local == null || r.updatedAt > local.updatedAt) {
                cur[r.id] = r
                changed = true
            }
        }
        if (changed) {
            _notes.value = cur
            save()
            refreshWidgets()
        }
        if (initial) {
            val byId = remote.associateBy { it.id }
            cur.values.filter { (byId[it.id]?.updatedAt ?: -1) < it.updatedAt }.forEach(::push)
        }
    }

    private fun put(n: Note) {
        _notes.value = _notes.value + (n.id to n)
        save()
        refreshWidgets()
        SyncWorker.kick(ctx)
    }

    fun refreshWidgets() {
        scope.launch { runCatching { NoteWidget().updateAll(ctx) } }
    }

    private fun push(n: Note) {
        val u = user ?: return
        // Firestore queues this (persistently) while offline.
        // syncedAt (server clock) lets the desktop poll for changes without trusting device clocks.
        col(u.uid).document(n.id).set(n.toMap() + ("syncedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { Log.w(TAG, "push failed", it) }
    }

    @Synchronized
    fun startListening() {
        val u = user ?: return
        listener?.remove()
        var first = true
        listener = col(u.uid).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "listen failed", err)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val docs = if (first) snap.documents
            else snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
            val notes = docs.filter { first || !it.metadata.hasPendingWrites() }.mapNotNull { d ->
                d.data?.let { Note.fromMap(d.id, it) }
            }
            applyRemote(notes, first)
            first = false
        }
    }

    @Synchronized
    fun stopListening() {
        listener?.remove()
        listener = null
    }

    /** One-shot reconcile, used by the background worker. */
    suspend fun syncOnce() {
        val u = user ?: return
        val snap = col(u.uid).get().await()
        applyRemote(snap.documents.mapNotNull { d -> d.data?.let { Note.fromMap(d.id, it) } }, initial = true)
    }

    private fun load(): Map<String, Note> = try {
        val arr = JSONArray(String(file.readFully()))
        (0 until arr.length()).map { Note.fromJson(arr.getJSONObject(it)) }.associateBy { it.id }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun save() {
        val out = file.startWrite()
        try {
            out.write(JSONArray(_notes.value.values.map { it.toJson() }).toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "save failed", e)
        }
    }

    companion object {
        private const val TAG = "NoteRepo"

        @Volatile
        private var instance: NoteRepo? = null

        fun get(ctx: Context): NoteRepo =
            instance ?: synchronized(this) { instance ?: NoteRepo(ctx.applicationContext).also { instance = it } }
    }
}
