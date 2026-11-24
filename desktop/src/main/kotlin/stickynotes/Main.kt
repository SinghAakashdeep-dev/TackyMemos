package stickynotes

import java.awt.AWTEvent
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.CheckboxMenuItem
import java.awt.MouseInfo
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main() {
    val profile = System.getenv("STICKY_PROFILE")?.takeIf { it.isNotBlank() }
    val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
    val dir = File(base, if (profile == null) "TackyMemos" else "TackyMemos-$profile")
    // The app used to be called Sticky Notes: carry its notes and sign-in over on first run.
    val old = File(base, if (profile == null) "StickyNotes" else "StickyNotes-$profile")
    if (!dir.exists() && old.isDirectory) runCatching { old.copyRecursively(dir) }

    // Single instance: a second launch asks the running copy to bring its notes forward.
    val port = 47800 + Math.floorMod((profile ?: "default").hashCode(), 100)
    val server = try {
        ServerSocket(port, 5, InetAddress.getLoopbackAddress())
    } catch (e: Exception) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(1) } }
        exitProcess(0)
    }

    System.setProperty("awt.useSystemAAFontSettings", "on")
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    SwingUtilities.invokeAndWait { App(dir).start() }
    System.getenv("STICKY_SELFTEST")?.let { SelfTest.run(App.instance, File(it)) }
    thread(isDaemon = true) {
        while (true) runCatching { server.accept().use { SwingUtilities.invokeLater { App.instance.bringAllToFront() } } }
    }
}

class App(dir: File) {
    val store = Store(dir)
    val sync = Sync(this, dir)
    var quitting = false
        private set
    internal val windows = LinkedHashMap<String, NoteWindow>()
    private var tray: TrayIcon? = null
    private var account: AccountDialog? = null

    companion object {
        lateinit var instance: App
    }

    fun start() {
        instance = this
        if (store.notes.isEmpty()) {
            val n = store.create("text", "yellow")
            store.update(n.id) {
                it.copy(text = "Welcome to Tacky Memos!\n\nRight-click a note to tear off a new one, turn it into a checklist, or change its color.\n\nDrag the top bar to move it.")
            }
        }
        store.live().forEach { open(it, null, false) }

        // Right-click anywhere on a note opens its menu.
        Toolkit.getDefaultToolkit().addAWTEventListener({ ev ->
            val e = ev as MouseEvent
            if (e.id == MouseEvent.MOUSE_RELEASED && e.isPopupTrigger) {
                val w = SwingUtilities.getWindowAncestor(e.component) ?: (e.component as? Window)
                if (w is NoteWindow) {
                    e.consume()
                    w.showMenu(e.component, e.x, e.y)
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK)

        installTray()
        // Keep the Windows startup entry pointing at this copy of the app (and under the new name).
        if (store.launchAtLogin && System.getenv("STICKY_SELFTEST") == null) Autostart.set(true)
        sync.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { store.saveNow() } })
    }

    fun quit() {
        quitting = true
        store.saveNow()
        tray?.let { SystemTray.getSystemTray().remove(it) }
        exitProcess(0)
    }

    // ---------- windows ----------

    private fun open(n: Note, at: java.awt.Point?, focus: Boolean): NoteWindow {
        windows[n.id]?.let { return it }
        val w = NoteWindow(this, n)
        val b = store.layout[n.id]
        w.setSize(b?.w ?: (260 + 2 * NoteWindow.MARGIN), b?.h ?: (260 + 2 * NoteWindow.MARGIN))
        when {
            at != null -> w.setLocation(at)
            b != null && isOnScreen(b) -> w.setLocation(b.x, b.y)
            else -> {
                val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                val k = windows.size % 8
                w.setLocation(area.x + area.width - w.width - 40 - k * 30, area.y + 40 + k * 30)
            }
        }
        windows[n.id] = w
        w.isVisible = true
        if (b == null || at != null) store.setLayout(n.id, Bounds(w.x, w.y, w.width, w.height))
        if (focus) w.focusEditor()
        return w
    }

    private fun isOnScreen(b: Bounds): Boolean =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { d ->
            val r = d.defaultConfiguration.bounds
            b.x + 40 < r.x + r.width && b.x + b.w - 40 > r.x && b.y >= r.y - 10 && b.y + 30 < r.y + r.height
        }

    private fun close(id: String) {
        windows.remove(id)?.let { it.allowClose = true; it.dispose() }
    }

    fun bringAllToFront() {
        windows.values.forEach { w ->
            w.isVisible = true
            w.toFront()
        }
    }

    // ---------- note actions ----------

    fun tearOff(from: NoteWindow?, type: String) {
        val color = from?.let { store.get(it.noteId)?.color } ?: "yellow"
        val n = store.create(type, color)
        // Peel the new note off right under the mouse pointer.
        val p = MouseInfo.getPointerInfo()?.location ?: java.awt.Point(200, 200)
        open(n, java.awt.Point(p.x - 30 - NoteWindow.MARGIN, p.y - 12 - NoteWindow.MARGIN), true)
        refreshTray()
    }

    fun convert(id: String) {
        store.update(id) { n ->
            if (n.isChecklist) n.copy(type = "text", text = n.items.joinToString("\n") { (if (it.done) "$TICKED " else "$BOX ") + it.text }, items = emptyList())
            else n.copy(
                type = "checklist",
                text = "",
                items = n.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { l -> Item(text = l.trimStart('-', '*', '•', BOX, TICKED).trim(), done = l.startsWith(TICKED)) }.filter { it.text.isNotEmpty() },
            )
        }?.let { windows[id]?.applyExternal(it) }
    }

    fun setColor(id: String, color: String) {
        store.update(id) { it.copy(color = color) }?.let { windows[id]?.applyExternal(it) }
    }

    fun setAlwaysOnTop(on: Boolean) {
        store.alwaysOnTop = on
        store.save()
        windows.values.forEach { it.isAlwaysOnTop = on }
        refreshTray()
    }

    fun setLaunchAtLogin(on: Boolean) {
        store.launchAtLogin = on
        store.save()
        Autostart.set(on)
        refreshTray()
    }

    fun delete(id: String, owner: NoteWindow) {
        val n = store.get(id) ?: return
        if ((n.text.isNotBlank() || n.items.isNotEmpty()) &&
            JOptionPane.showConfirmDialog(owner, "Delete this note?\nIt will also be removed from your other synced devices.",
                "Tacky Memos", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION
        ) return
        store.remove(id)
        close(id)
        refreshTray()
    }

    /** Called by the sync client (on the event thread) for each note it applied. */
    fun onRemoteApplied(n: Note) {
        when {
            n.deleted -> close(n.id)
            windows.containsKey(n.id) -> windows.getValue(n.id).applyExternal(n)
            else -> open(n, null, false)
        }
    }

    fun showAccount() {
        account?.let { it.toFront(); return }
        account = AccountDialog(this).also { d ->
            d.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) { account = null; refreshTray() }
            })
            d.isVisible = true
        }
    }

    // ---------- tray ----------

    private fun installTray() {
        if (!SystemTray.isSupported()) return
        val t = TrayIcon(trayImage(), "Tacky Memos")
        t.isImageAutoSize = true
        t.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) bringAllToFront() }
            override fun mousePressed(e: MouseEvent) { refreshTray() }
        })
        SystemTray.getSystemTray().add(t)
        tray = t
        refreshTray()
    }

    fun refreshTray() {
        val t = tray ?: return
        val m = PopupMenu()
        fun add(label: String, action: (() -> Unit)?) = MenuItem(label).also { mi ->
            if (action == null) mi.isEnabled = false else mi.addActionListener { SwingUtilities.invokeLater(action) }
            m.add(mi)
        }
        fun check(label: String, on: Boolean, action: (Boolean) -> Unit) = m.add(CheckboxMenuItem(label, on).apply {
            addItemListener { val v = state; SwingUtilities.invokeLater { action(v) } }
        })
        add("New note") { tearOff(null, "text") }
        add("New checklist") { tearOff(null, "checklist") }
        add("Bring notes to front") { bringAllToFront() }
        m.addSeparator()
        check("Always on top", store.alwaysOnTop) { setAlwaysOnTop(it) }
        check("Start with Windows", store.launchAtLogin) { setLaunchAtLogin(it) }
        m.addSeparator()
        when {
            !sync.configured -> add("Sync: not set up (see README)", null)
            sync.email != null -> {
                add("Synced as ${sync.email}${if (sync.online) "" else " (offline)"}", null)
                add("Account…") { showAccount() }
            }
            else -> add("Sign in to sync…") { showAccount() }
        }
        sync.lastError?.let { add("Sync error: " + it.take(60), null) }
        m.addSeparator()
        add("Open data folder") { runCatching { java.awt.Desktop.getDesktop().open(store.dir) } }
        add("Quit") { quit() }
        t.popupMenu = m
        t.toolTip = "Tacky Memos — ${store.live().size} notes"
    }

    /** A small yellow sticky note with a folded corner. */
    private fun trayImage(): BufferedImage {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val xs = intArrayOf(3, 29, 29, 20, 3)
        val ys = intArrayOf(3, 3, 20, 29, 29)
        g.color = Color(255, 240, 110); g.fillPolygon(xs, ys, 5)
        g.color = Color(200, 150, 40); g.stroke = java.awt.BasicStroke(2f); g.drawPolygon(xs, ys, 5)
        g.color = Color(235, 200, 70); g.fillPolygon(intArrayOf(29, 20, 20), intArrayOf(20, 20, 29), 3)
        g.color = Color(150, 120, 50)
        g.drawLine(8, 10, 24, 10); g.drawLine(8, 16, 24, 16); g.drawLine(8, 22, 15, 22)
        g.dispose()
        return img
    }
}

/** "Start with Windows": a per-user Run entry that launches the jar with javaw (no console window). */
object Autostart {
    private fun command(): String {
        val javaw = File(System.getProperty("java.home"), "bin\\javaw.exe").path
        val jar = File(App::class.java.protectionDomain.codeSource.location.toURI()).path
        return "\"$javaw\" -jar \"$jar\""
    }

    fun set(on: Boolean) {
        val key = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        // Also drop the entry from when the app was called Sticky Notes.
        val dropOld = "Remove-ItemProperty -Path '$key' -Name 'StickyNotes' -ErrorAction SilentlyContinue; "
        val ps = dropOld + if (on) "Set-ItemProperty -Path '$key' -Name 'TackyMemos' -Value '${command().replace("'", "''")}'"
        else "Remove-ItemProperty -Path '$key' -Name 'TackyMemos' -ErrorAction SilentlyContinue"
        // -EncodedCommand avoids every quoting problem with paths containing spaces.
        val encoded = Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
        thread { runCatching { ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).start().waitFor() } }
    }
}
