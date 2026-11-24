package stickynotes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread

/**
 * Two-way sync with Firestore over its REST API.
 *  - Sign-in: Firebase Auth email/password. The refresh token is kept in account.json in the data folder.
 *  - Push: each locally changed note is written with a server timestamp "syncedAt".
 *  - Pull: every 20 s, ask only for notes whose syncedAt is newer than the last one seen
 *    (server clock, so phone/laptop clock differences don't matter).
 * Conflicts: last writer wins, by the note's updatedAt.
 */
class Sync(private val app: App, private val dir: File) {
    var configured = false; private set
    val email get() = account?.email
    @Volatile var online = true; private set
    @Volatile var lastError: String? = null; private set

    private class Config(val apiKey: String, val projectId: String, val emulator: String?)
    private class Account(val uid: String, val email: String, var refreshToken: String)

    private var cfg: Config? = null
    @Volatile private var account: Account? = null
    private var idToken: String? = null
    private var idExpiry = Instant.EPOCH
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val wake = Semaphore(0)
    private val busy = ReentrantLock()
    private val debounce = Timer(1500) { wake.release() }.apply { isRepeats = false }

    private val c get() = cfg!!
    private val authBase get() = c.emulator?.let { "http://$it:9099/identitytoolkit.googleapis.com/v1" } ?: "https://identitytoolkit.googleapis.com/v1"
    private val tokenUrl get() = (c.emulator?.let { "http://$it:9099/securetoken.googleapis.com" } ?: "https://securetoken.googleapis.com") + "/v1/token?key=" + c.apiKey
    private val dbPath get() = "projects/${c.projectId}/databases/(default)/documents"
    private val fsBase get() = (c.emulator?.let { "http://$it:8080" } ?: "https://firestore.googleapis.com") + "/v1/" + dbPath

    fun start() {
        cfg = loadConfig()
        configured = cfg != null
        if (!configured) return
        account = loadAccount()
        app.store.onLocalChange = { debounce.restart() }
        thread(isDaemon = true, name = "sync") {
            while (true) {
                if (account != null) syncNow()
                wake.tryAcquire(20, TimeUnit.SECONDS)
                wake.drainPermits()
            }
        }
    }

    private fun loadConfig(): Config? {
        val jarDir = runCatching { File(Sync::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        for (f in listOfNotNull(File(dir, "firebase-config.json"), jarDir?.let { File(it, "firebase-config.json") }, jarDir?.parentFile?.let { File(it, "firebase-config.json") })) {
            if (!f.exists()) continue
            runCatching {
                val o = JSONObject(f.readText())
                val key = o.optString("apiKey")
                val pid = o.optString("projectId")
                if (key.isNotEmpty() && !key.startsWith("YOUR_") && pid.isNotEmpty()) return Config(key, pid, null)
            }
        }
        System.getenv("STICKY_EMULATOR")?.takeIf { it.isNotBlank() }?.let { return Config("demo-key", "demo-stickynotes", it) }
        return null
    }

    // ---------- account ----------

    private val accountFile get() = File(dir, "account.json")

    private fun loadAccount(): Account? = runCatching {
        val o = JSONObject(accountFile.readText())
        Account(o.getString("uid"), o.getString("email"), o.getString("refreshToken"))
    }.getOrNull()

    private fun saveAccount() {
        val a = account
        if (a == null) accountFile.delete()
        else accountFile.writeText(JSONObject().put("uid", a.uid).put("email", a.email).put("refreshToken", a.refreshToken).toString())
    }

    /** Blocking; call off the event thread. Returns an error message, or null on success. */
    fun signIn(email: String, password: String, create: Boolean): String? {
        if (cfg == null) return "Sync is not set up. See README."
        return try {
            val url = "$authBase/accounts:${if (create) "signUp" else "signInWithPassword"}?key=${c.apiKey}"
            val res = post(url, JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString(), "application/json")
            val body = JSONObject(res.body())
            if (res.statusCode() !in 200..299) return friendly(body.optJSONObject("error")?.optString("message") ?: "Sign-in failed")
            account = Account(body.getString("localId"), body.getString("email"), body.getString("refreshToken"))
            idToken = body.getString("idToken")
            idExpiry = Instant.now().plusSeconds(body.getString("expiresIn").toLong() - 60)
            saveAccount()
            SwingUtilities.invokeAndWait {
                // Start from scratch and upload everything we have.
                app.store.syncCursor = null
                app.store.dirty += app.store.notes.keys
                app.store.save()
            }
            wake.release()
            null
        } catch (e: java.io.IOException) {
            "No internet connection."
        }
    }

    fun signOut() {
        account = null
        idToken = null
        saveAccount()
        app.store.syncCursor = null
        app.store.save()
    }

    private fun friendly(code: String) = when {
        code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") || code.startsWith("EMAIL_NOT_FOUND") -> "Wrong email or password."
        code.startsWith("EMAIL_EXISTS") -> "That email already has an account. Use Sign in."
        code.startsWith("WEAK_PASSWORD") -> "Password must be at least 6 characters."
        code.startsWith("INVALID_EMAIL") -> "That email address is not valid."
        code.startsWith("OPERATION_NOT_ALLOWED") -> "Email/password sign-in is not enabled in the Firebase console."
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Wait a bit and try again."
        else -> code
    }

    private class SyncException(msg: String) : Exception(msg)

    private fun token(): String {
        idToken?.let { if (Instant.now().isBefore(idExpiry)) return it }
        val a = account ?: throw SyncException("Not signed in")
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(a.refreshToken, Charsets.UTF_8)
        val res = post(tokenUrl, form, "application/x-www-form-urlencoded")
        val body = JSONObject(res.body())
        if (res.statusCode() !in 200..299) {
            val msg = body.optJSONObject("error")?.optString("message") ?: ""
            if (listOf("TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN").any { msg.contains(it) }) {
                SwingUtilities.invokeAndWait { signOut() }
                throw SyncException("Signed out: please sign in again.")
            }
            throw SyncException("Could not refresh sign-in: $msg")
        }
        a.refreshToken = body.getString("refresh_token")
        idExpiry = Instant.now().plusSeconds(body.getString("expires_in").toLong() - 60)
        saveAccount()
        return body.getString("id_token").also { idToken = it }
    }

    // ---------- loop ----------

    fun syncNow() {
        if (!busy.tryLock()) return
        try {
            pull()
            push()
            online = true
            lastError = null
        } catch (e: java.io.IOException) {
            online = false
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
        } finally {
            busy.unlock()
            SwingUtilities.invokeLater { app.refreshTray() }
        }
    }

    private fun post(url: String, body: String, type: String, bearer: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun firestore(url: String, body: JSONObject): String {
        val res = post(url, body.toString(), "application/json", token())
        if (res.statusCode() !in 200..299) throw SyncException("${res.statusCode()} ${res.body().take(200)}")
        return res.body()
    }

    private fun <T> onUi(f: () -> T): T {
        var r: Any? = null
        SwingUtilities.invokeAndWait { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun pull() {
        val acct = account ?: return
        val cursor = onUi { app.store.syncCursor }
        val initial = cursor == null
        // Ask a little before the cursor so writes that committed out of order aren't missed.
        val since = if (cursor == null) Instant.EPOCH else Instant.parse(cursor).minusSeconds(5)
        val query = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "notes")))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", "syncedAt"))
                .put("op", "GREATER_THAN")
                .put("value", JSONObject().put("timestampValue", since.toString()))))
            .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "syncedAt")))))
        val rows = JSONArray(firestore("$fsBase/users/${acct.uid}:runQuery", query))
        val notes = ArrayList<Note>()
        var maxSynced = cursor?.let { Instant.parse(it) }
        for (i in 0 until rows.length()) {
            val doc = rows.getJSONObject(i).optJSONObject("document") ?: continue
            val fields = doc.getJSONObject("fields")
            notes += Firestore.toNote(doc.getString("name").substringAfterLast('/'), fields)
            fields.optJSONObject("syncedAt")?.optString("timestampValue")?.takeIf { it.isNotEmpty() }?.let {
                val t = Instant.parse(it)
                if (maxSynced == null || t.isAfter(maxSynced)) maxSynced = t
            }
        }
        if (account !== acct) return // signed out meanwhile
        onUi {
            for (n in notes) if (app.store.applyRemote(n)) app.onRemoteApplied(n)
            if (initial) {
                // Anything we have that the server lacks (or has older) gets uploaded.
                val remote = notes.associate { it.id to it.updatedAt }
                for (local in app.store.notes.values) if ((remote[local.id] ?: -1) < local.updatedAt) app.store.dirty += local.id
            }
            app.store.syncCursor = (maxSynced ?: Instant.now().minusSeconds(60)).toString()
            app.store.save()
        }
    }

    private fun push() {
        val acct = account ?: return
        val batch = onUi { app.store.dirty.mapNotNull { app.store.get(it) }.take(400) }
        if (batch.isEmpty()) return
        val writes = JSONArray()
        for (n in batch) writes.put(JSONObject()
            .put("update", JSONObject().put("name", "$dbPath/users/${acct.uid}/notes/${n.id}").put("fields", Firestore.fromNote(n)))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "syncedAt").put("setToServerValue", "REQUEST_TIME"))))
        firestore("$fsBase:commit", JSONObject().put("writes", writes))
        onUi {
            for (sent in batch) {
                // Only clear if nothing changed locally while the request was in flight.
                val cur = app.store.get(sent.id)
                if (cur == null || cur.updatedAt == sent.updatedAt) app.store.dirty -= sent.id
            }
            app.store.save()
        }
    }
}

/** Converts notes to and from Firestore's typed REST JSON. */
object Firestore {
    private fun s(v: String) = JSONObject().put("stringValue", v)
    private fun i(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun b(v: Boolean) = JSONObject().put("booleanValue", v)

    fun fromNote(n: Note): JSONObject = JSONObject()
        .put("id", s(n.id)).put("type", s(n.type)).put("text", s(n.text))
        .put("items", JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(n.items.map {
            JSONObject().put("mapValue", JSONObject().put("fields", JSONObject().put("id", s(it.id)).put("text", s(it.text)).put("done", b(it.done))))
        }))))
        .put("color", s(n.color)).put("createdAt", i(n.createdAt)).put("updatedAt", i(n.updatedAt)).put("deleted", b(n.deleted))

    private fun str(o: JSONObject?, k: String) = o?.optJSONObject(k)?.optString("stringValue")
    private fun bool(o: JSONObject?, k: String) = o?.optJSONObject(k)?.optBoolean("booleanValue") ?: false
    private fun long(o: JSONObject?, k: String): Long {
        val v = o?.optJSONObject(k) ?: return 0
        return when {
            v.has("integerValue") -> v.getString("integerValue").toLong()
            v.has("doubleValue") -> v.getDouble("doubleValue").toLong()
            else -> 0
        }
    }

    fun toNote(id: String, f: JSONObject): Note {
        val arr = f.optJSONObject("items")?.optJSONObject("arrayValue")?.optJSONArray("values")
        val items = (0 until (arr?.length() ?: 0)).mapNotNull { idx ->
            val m = arr!!.getJSONObject(idx).optJSONObject("mapValue")?.optJSONObject("fields") ?: return@mapNotNull null
            Item(str(m, "id") ?: UUID.randomUUID().toString(), str(m, "text") ?: "", bool(m, "done"))
        }
        return Note(
            id = id, type = str(f, "type") ?: "text", text = str(f, "text") ?: "", items = items,
            color = str(f, "color") ?: "yellow", createdAt = long(f, "createdAt"), updatedAt = long(f, "updatedAt"),
            deleted = bool(f, "deleted"),
        ).normalized()
    }
}
