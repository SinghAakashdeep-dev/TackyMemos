package stickynotes

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.MouseInfo
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.font.TextAttribute
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

val INK = Color(0x3B3320)
val MUTED = Color(0x3B, 0x33, 0x20, 0x80)
val FONT = Font("Segoe UI", Font.PLAIN, 14)

/** Handwriting font for note content: Ink Free ships with Windows 10/11; fall back to similar ones. */
val HAND: Font = run {
    val installed = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    val family = listOf("Ink Free", "Segoe Print", "Comic Sans MS").firstOrNull { it in installed } ?: "Segoe UI"
    Font(family, Font.PLAIN, if (family == "Ink Free") 17 else 14)
}

const val BOX = '☐'
const val TICKED = '☑'
/** Typing this in a note turns into a tickable checkbox. */
const val BOX_SHORTCUT = "(*)"

/** One sticky note on the desktop: frameless, draggable, resizable from its edges. */
class NoteWindow(private val app: App, initial: Note) : JFrame("Tacky memo") {
    val noteId: String = initial.id
    var allowClose = false
    private var note = initial

    /** The paper: rounded corners and a faint top-to-bottom shade. */
    private val root = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, RADIUS, RADIUS)
            val c = background
            g2.paint = GradientPaint(0f, 0f, blend(c, Color.WHITE, 0.25f), 0f, height.toFloat(), blend(c, Color(0x8A6D1F), 0.06f))
            g2.fill(shape)
            g2.color = Color(0, 0, 0, 28)
            g2.draw(shape)
            g2.dispose()
        }
    }.apply { isOpaque = false }

    // ----- tilt: resting notes lean a little; they straighten while you point at or edit them -----
    private val restAngle = tiltFor(initial.id)
    private var angle = 0.0
    private var targetAngle = 0.0
    private var snapshot: BufferedImage? = null
    internal var menuOpen = false
    private var dragging = false

    /** Transparent window area around the paper that holds the shadow and the tilted corners. */
    private val stage = object : JPanel(BorderLayout()) {
        override fun paint(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val r = root.bounds
            if (angle != 0.0) g2.rotate(Math.toRadians(angle), r.centerX, r.centerY)
            paintShadow(g2, r)
            if (root.isVisible) {
                g2.dispose()
                super.paint(g)  // straight: real, editable components
            } else {
                snapshot?.let { g2.drawImage(it, r.x, r.y, r.width, r.height, null) }
                g2.dispose()
            }
        }
    }.apply { isOpaque = false }

    private val anim = Timer(15) { stepTilt() }
    private val hoverPoll = Timer(120) {
        val p = MouseInfo.getPointerInfo()?.location
        val busy = (p != null && bounds.contains(p)) || isFocused || menuOpen || dragging || !isShowing
        setTilt(if (busy) 0.0 else restAngle)
    }

    private fun setTilt(t: Double) {
        if (t == targetAngle && (anim.isRunning || angle == t)) return
        if (angle == 0.0 && t != 0.0 && root.isVisible) {
            // Leaving the straight state: freeze the current look into an image to draw rotated.
            snapshot = paintSnapshot() ?: return
            root.isVisible = false
        }
        targetAngle = t
        anim.start()
    }

    private fun stepTilt() {
        val d = targetAngle - angle
        angle = if (kotlin.math.abs(d) < 0.1) targetAngle else angle + d * 0.3
        if (angle == targetAngle) {
            anim.stop()
            if (angle == 0.0) {
                root.isVisible = true
                stage.revalidate()
            }
        }
        stage.repaint()
    }

    private fun paintSnapshot(): BufferedImage? {
        if (root.width <= 0 || root.height <= 0) return null
        val s = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val img = BufferedImage(kotlin.math.ceil(root.width * s).toInt(), kotlin.math.ceil(root.height * s).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.scale(s, s)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        root.paint(g)
        g.dispose()
        return img
    }

    /** Re-take the tilted picture after content changes while resting (e.g. a sync update). */
    private fun refreshSnapshot() {
        if (!root.isVisible) {
            root.isVisible = true
            stage.validate()
            snapshot = paintSnapshot() ?: snapshot
            root.isVisible = false
            stage.repaint()
        }
    }
    private val header = JPanel(BorderLayout())
    private val title = JLabel()
    private val textArea = JTextPane()
    private val textScroll = transparentScroll(textArea)
    private val itemsPanel = JPanel()
    internal val addField = JTextField()
    internal val itemFields get() = itemsPanel.components.mapNotNull { r -> (r as? JPanel)?.components?.filterIsInstance<JTextField>()?.firstOrNull() }
    internal val itemChecks get() = itemsPanel.components.mapNotNull { r -> (r as? JPanel)?.components?.filterIsInstance<JCheckBox>()?.firstOrNull() }
    internal val textAreaForTest get() = textArea
    private val listScroll: JScrollPane
    private val saveTimer = Timer(400) { flushSave() }.apply { isRepeats = false }
    private val boundsTimer = Timer(500) { saveBounds() }.apply { isRepeats = false }
    private var suppress = false   // ignore document events while we set text programmatically
    private var deferred = false   // a remote update arrived while the user was typing

    init {
        isUndecorated = true
        type = Type.UTILITY            // no taskbar button, not in Alt+Tab
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(160 + 2 * MARGIN, 110 + 2 * MARGIN)
        isAlwaysOnTop = app.store.alwaysOnTop
        autoRequestFocusOnShow(false)
        background = Color(0, 0, 0, 0)   // see-through window; only the paper and its shadow are drawn
        rootPane.isOpaque = false
        contentPane = stage
        stage.border = BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN)
        stage.add(root, BorderLayout.CENTER)
        root.border = BorderFactory.createEmptyBorder(EDGE, EDGE, EDGE, EDGE)
        stage.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setTilt(0.0)
            override fun mousePressed(e: MouseEvent) = setTilt(0.0)
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                // Settle into the resting tilt a moment after appearing.
                Timer(900) { hoverPoll.start() }.apply { isRepeats = false }.start()
            }
            override fun windowClosed(e: WindowEvent) { hoverPoll.stop(); anim.stop() }
        })

        // ----- header: drag handle + buttons -----
        header.isOpaque = false
        header.preferredSize = Dimension(0, 24)
        header.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        title.font = FONT.deriveFont(11f)
        title.foreground = MUTED
        title.border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(headerButton("+", "Tear off new note (Ctrl+N)") { app.tearOff(this@NoteWindow, "text") })
            add(headerButton("≡", "Menu") { showMenu(it) })
        }
        header.add(title, BorderLayout.CENTER)
        header.add(buttons, BorderLayout.EAST)
        val drag = object : MouseAdapter() {
            var offset: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) { offset = Point(e.xOnScreen - x, e.yOnScreen - y); dragging = true }
            }
            override fun mouseDragged(e: MouseEvent) {
                offset?.let { setLocation(e.xOnScreen - it.x, e.yOnScreen - it.y) }
            }
            override fun mouseReleased(e: MouseEvent) { offset = null; dragging = false }
        }
        header.addMouseListener(drag)
        header.addMouseMotionListener(drag)
        title.addMouseListener(drag)
        title.addMouseMotionListener(drag)
        root.add(header, BorderLayout.NORTH)

        // ----- plain text body -----
        styleText(textArea)
        textArea.border = BorderFactory.createEmptyBorder(4, 6, 6, 6)
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = textEdited()
            override fun removeUpdate(e: DocumentEvent) = textEdited()
            override fun changedUpdate(e: DocumentEvent) {} // styling only
        })
        // Click a ☐/☑ to tick or untick it.
        val boxes = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                boxAt(e.point)?.let { toggleBox(it); e.consume() }
            }
            override fun mouseMoved(e: MouseEvent) {
                textArea.cursor = Cursor.getPredefinedCursor(if (boxAt(e.point) != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
            }
        }
        textArea.addMouseListener(boxes)
        textArea.addMouseMotionListener(boxes)

        // ----- checklist body -----
        itemsPanel.layout = BoxLayout(itemsPanel, BoxLayout.Y_AXIS)
        itemsPanel.isOpaque = false
        styleText(addField)
        addField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = onAddKey(e)
        })
        val addRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            add(JLabel("+", JLabel.CENTER).apply { foreground = MUTED; font = FONT; preferredSize = Dimension(22, 20) }, BorderLayout.WEST)
            add(placeholder(addField, "Add item"), BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val listBody = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(itemsPanel)
            add(addRow)
            add(Box.createVerticalGlue())
        }
        listScroll = transparentScroll(listBody)

        // ----- window behaviour -----
        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = boundsTimer.restart()
            override fun componentResized(e: ComponentEvent) = boundsTimer.restart()
        })
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) = onEditingDone()
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (allowClose || app.quitting) dispose()
            }
        })
        installResize()
        rootPane.registerKeyboardAction({ app.tearOff(this, "text") },
            KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({ app.tearOff(this, "checklist") },
            KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW)

        render()
    }

    private fun autoRequestFocusOnShow(v: Boolean) { isAutoRequestFocus = v }

    // ---------- rendering ----------

    private fun render() {
        root.background = NOTE_COLORS.getValue(note.color)
        val body = if (note.isChecklist) listScroll else textScroll
        val other = if (note.isChecklist) textScroll else listScroll
        if (other.parent == root) root.remove(other)
        if (body.parent != root) root.add(body, BorderLayout.CENTER)
        if (note.isChecklist) renderItems(null, null)
        else if (plainText() != note.text) {
            suppress = true
            textArea.text = note.text
            textArea.caretPosition = 0
            suppress = false
            restyle()
        }
        renderTitle()
        root.revalidate()
        root.repaint()
        refreshSnapshot()
    }

    // ---------- (*) checkboxes inside text notes ----------

    private fun textEdited() {
        if (suppress) return
        note = note.copy(text = plainText())
        queueSave()
        // Document can't be changed inside its own listener, so expand/restyle right after.
        SwingUtilities.invokeLater {
            expandShortcuts()
            restyle()
        }
    }

    /** Text with plain 
 line breaks (JTextPane.getText() would give 
 on Windows). */
    internal fun plainText(): String = textArea.document.let { it.getText(0, it.length) }

    /** Replace every "(*)" with "☐ ". */
    internal fun expandShortcuts() {
        val doc = textArea.document
        var text = doc.getText(0, doc.length)
        var i = text.indexOf(BOX_SHORTCUT)
        while (i >= 0) {
            val spaceFollows = text.getOrNull(i + BOX_SHORTCUT.length) == ' '
            doc.remove(i, BOX_SHORTCUT.length)
            doc.insertString(i, if (spaceFollows) "$BOX" else "$BOX ", null)
            text = doc.getText(0, doc.length)
            i = text.indexOf(BOX_SHORTCUT)
        }
    }

    private fun boxAt(p: Point): Int? {
        val i = textArea.viewToModel2D(p)
        val text = plainText()
        for (c in listOf(i, i - 1)) {
            if (c < 0 || c >= text.length || (text[c] != BOX && text[c] != TICKED)) continue
            val a = textArea.modelToView2D(c) ?: continue
            val b = textArea.modelToView2D(c + 1) ?: continue
            if (p.x >= a.x - 2 && p.x <= maxOf(b.x, a.x + 12) + 2 && p.y >= a.y && p.y <= a.y + a.height) return c
        }
        return null
    }

    internal fun toggleBox(i: Int) {
        val doc = textArea.document
        val c = doc.getText(i, 1)[0]
        doc.remove(i, 1)
        doc.insertString(i, if (c == BOX) "$TICKED" else "$BOX", null)
        flushSave()
    }

    /** Handwriting for text, a symbol font for the boxes, and strike-through for ticked lines. */
    private fun restyle() {
        val doc = textArea.styledDocument
        val text = doc.getText(0, doc.length)
        val base = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, HAND.family)
            StyleConstants.setFontSize(this, HAND.size)
            StyleConstants.setForeground(this, INK)
        }
        doc.setCharacterAttributes(0, text.length, base, true)
        var start = 0
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith(TICKED)) {
                doc.setCharacterAttributes(start, line.length, SimpleAttributeSet().apply {
                    StyleConstants.setStrikeThrough(this, true)
                    StyleConstants.setForeground(this, MUTED)
                }, false)
            }
            start += line.length + 1
        }
        text.forEachIndexed { i, c ->
            if (c == BOX || c == TICKED) doc.setCharacterAttributes(i, 1, SimpleAttributeSet().apply {
                StyleConstants.setFontFamily(this, "Segoe UI Symbol")
                StyleConstants.setStrikeThrough(this, false)
                StyleConstants.setForeground(this, INK)
            }, false)
        }
        textArea.inputAttributes.apply { removeAttributes(copyAttributes()); addAttributes(base) } // what the user types next
    }

    private fun renderTitle() {
        title.text = when {
            !note.isChecklist -> ""
            note.items.isEmpty() -> "Checklist"
            else -> "${note.items.count { it.done }}/${note.items.size} done"
        }
    }

    private fun renderItems(focusId: String?, caret: Int?) {
        var fid = focusId
        var pos = caret
        val owner = focusOwner
        if (fid == null && owner is JTextField && owner.getClientProperty("itemId") != null) {
            fid = owner.getClientProperty("itemId") as String
            pos = owner.caretPosition
        }
        itemsPanel.removeAll()
        var focusField: JTextField? = null
        for (item in note.items) {
            val row = JPanel(BorderLayout()).apply { isOpaque = false }
            val cb = JCheckBox().apply {
                isOpaque = false
                isSelected = item.done
                isFocusable = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            val field = JTextField(item.text)
            styleText(field)
            field.putClientProperty("itemId", item.id)
            if (item.done) {
                field.foreground = MUTED
                field.font = HAND.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
            }
            val del = JButton("×").apply {
                isFocusable = false
                isContentAreaFilled = false
                isBorderPainted = false
                foreground = MUTED
                font = FONT.deriveFont(15f)
                margin = java.awt.Insets(0, 4, 0, 4)
                toolTipText = "Remove item"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            cb.addActionListener {
                updateItem(item.id) { it.copy(done = cb.isSelected) }
                flushSave()
                renderItems(null, null)
            }
            field.document.addDocumentListener(onChange {
                if (!suppress) {
                    updateItem(item.id) { it.copy(text = field.text) }
                    queueSave()
                }
            })
            field.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) = onItemKey(e, item.id, field)
            })
            del.addActionListener { removeItem(item.id, false) }
            row.add(cb, BorderLayout.WEST)
            row.add(field, BorderLayout.CENTER)
            row.add(del, BorderLayout.EAST)
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
            itemsPanel.add(row)
            if (item.id == fid) focusField = field
        }
        itemsPanel.revalidate()
        itemsPanel.repaint()
        focusField?.let { f ->
            SwingUtilities.invokeLater {
                f.requestFocusInWindow()
                f.caretPosition = minOf(pos ?: f.text.length, f.text.length)
            }
        }
    }

    // ---------- editing ----------

    private fun updateItem(id: String, change: (Item) -> Item) {
        note = note.copy(items = note.items.map { if (it.id == id) change(it) else it })
    }

    private fun queueSave() {
        saveTimer.restart()
        renderTitle()
    }

    private fun flushSave() {
        saveTimer.stop()
        val snap = note
        app.store.update(noteId) { it.copy(type = snap.type, text = snap.text, items = snap.items) }?.let {
            note = note.copy(updatedAt = it.updatedAt)
        }
        renderTitle()
    }

    private fun onEditingDone() {
        if (saveTimer.isRunning) flushSave()
        if (!deferred) return
        deferred = false
        // The store holds the merged truth (last writer wins).
        app.store.get(noteId)?.let { note = it; render() }
    }

    private val isEditing get() = isFocused && focusOwner is JTextComponent

    private fun removeItem(id: String, focusPrev: Boolean) {
        val idx = note.items.indexOfFirst { it.id == id }
        if (idx < 0) return
        note = note.copy(items = note.items.filterNot { it.id == id })
        flushSave()
        val prev = note.items.getOrNull(maxOf(0, idx - 1))
        if (focusPrev && prev != null) renderItems(prev.id, null)
        else {
            renderItems("", null)
            if (focusPrev) addField.requestFocusInWindow()
        }
    }

    private fun onItemKey(e: KeyEvent, id: String, field: JTextField) {
        val idx = note.items.indexOfFirst { it.id == id }
        if (idx < 0) return
        when {
            e.keyCode == KeyEvent.VK_ENTER -> {
                e.consume()
                val pos = field.caretPosition
                val fresh = Item(text = field.text.substring(pos))
                val items = note.items.toMutableList()
                items[idx] = items[idx].copy(text = field.text.substring(0, pos))
                items.add(idx + 1, fresh)
                note = note.copy(items = items)
                flushSave()
                renderItems(fresh.id, 0)
            }
            e.keyCode == KeyEvent.VK_BACK_SPACE && field.caretPosition == 0 && field.selectedText == null -> {
                e.consume()
                if (idx == 0 && field.text.isNotEmpty()) return
                if (idx > 0) {
                    val prev = note.items[idx - 1]
                    val at = prev.text.length
                    val items = note.items.toMutableList()
                    items[idx - 1] = prev.copy(text = prev.text + field.text)
                    items.removeAt(idx)
                    note = note.copy(items = items)
                    flushSave()
                    renderItems(prev.id, at)
                } else removeItem(id, true)
            }
            e.keyCode == KeyEvent.VK_UP && idx > 0 -> {
                e.consume()
                renderItems(note.items[idx - 1].id, null)
            }
            e.keyCode == KeyEvent.VK_DOWN -> {
                e.consume()
                if (idx < note.items.size - 1) renderItems(note.items[idx + 1].id, null) else addField.requestFocusInWindow()
            }
        }
    }

    private fun onAddKey(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER && addField.text.isNotBlank()) {
            note = note.copy(items = note.items + Item(text = addField.text.trim()))
            addField.text = ""
            flushSave()
            renderItems("", null)
            addField.requestFocusInWindow()
            SwingUtilities.invokeLater { listScroll.verticalScrollBar.value = listScroll.verticalScrollBar.maximum }
        } else if (e.keyCode == KeyEvent.VK_UP && note.items.isNotEmpty()) {
            renderItems(note.items.last().id, null)
        }
    }

    fun focusEditor() {
        toFront()
        requestFocus()
        SwingUtilities.invokeLater { (if (note.isChecklist) addField else textArea).requestFocusInWindow() }
    }

    // ---------- updates from outside (sync, menu) ----------

    fun applyExternal(n: Note) {
        val typeChanged = n.type != note.type
        root.background = NOTE_COLORS.getValue(n.color)
        root.repaint()
        if (isEditing && !typeChanged) {
            note = note.copy(color = n.color)
            deferred = true
            return
        }
        saveTimer.stop()
        note = n
        render()
    }

    // ---------- context menu ----------

    fun showMenu(at: Component, x: Int = 0, y: Int = at.height) {
        if (saveTimer.isRunning) flushSave()
        val cur = app.store.get(noteId) ?: return
        val m = JPopupMenu()
        m.add(item("Tear off new note", "Ctrl+N") { app.tearOff(this, "text") })
        m.add(item("Tear off new checklist", "Ctrl+Shift+N") { app.tearOff(this, "checklist") })
        m.addSeparator()
        m.add(item(if (cur.isChecklist) "Convert to text note" else "Convert to checklist") { app.convert(noteId) })
        val colors = JMenu("Color")
        for ((name, c) in NOTE_COLORS) {
            colors.add(JRadioButtonMenuItem(name.replaceFirstChar { it.uppercase() }, Swatch(c), cur.color == name).apply {
                addActionListener { app.setColor(noteId, name) }
            })
        }
        m.add(colors)
        m.add(JCheckBoxMenuItem("Always on top", app.store.alwaysOnTop).apply {
            addActionListener { app.setAlwaysOnTop(isSelected) }
        })
        m.addSeparator()
        m.add(item("Delete note") { app.delete(noteId, this) })
        menuOpen = true
        m.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) { menuOpen = false }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) { menuOpen = false }
        })
        m.show(at, x, y)
    }

    private fun item(text: String, accel: String? = null, action: () -> Unit) = JMenuItem(text).apply {
        if (accel != null) accelerator = KeyStroke.getKeyStroke(if (accel.contains("Shift")) "control shift N" else "control N")
        addActionListener { action() }
    }

    private class Swatch(val c: Color) : Icon {
        override fun paintIcon(comp: Component?, g: Graphics, x: Int, y: Int) {
            g.color = c; g.fillRect(x, y, 14, 14)
            g.color = MUTED; g.drawRect(x, y, 13, 13)
        }
        override fun getIconWidth() = 14
        override fun getIconHeight() = 14
    }

    // ---------- window plumbing ----------

    private fun saveBounds() {
        if (!isShowing) return
        app.store.setLayout(noteId, Bounds(x, y, width, height))
    }

    /** Resize from the edges/corners (the frameless window has no system border). */
    private fun installResize() {
        val r = object : MouseAdapter() {
            var edge = 0
            var start: Rectangle? = null
            var startMouse: Point? = null

            fun edgeAt(p: Point): Int {
                var e = 0
                if (p.x < EDGE + 2) e = e or W
                if (p.x >= root.width - EDGE - 2) e = e or E
                if (p.y < EDGE + 2) e = e or N
                if (p.y >= root.height - EDGE - 2) e = e or S
                return e
            }

            override fun mouseMoved(e: MouseEvent) {
                root.cursor = Cursor.getPredefinedCursor(
                    when (edgeAt(e.point)) {
                        N -> Cursor.N_RESIZE_CURSOR; S -> Cursor.S_RESIZE_CURSOR
                        E -> Cursor.E_RESIZE_CURSOR; W -> Cursor.W_RESIZE_CURSOR
                        N or E -> Cursor.NE_RESIZE_CURSOR; N or W -> Cursor.NW_RESIZE_CURSOR
                        S or E -> Cursor.SE_RESIZE_CURSOR; S or W -> Cursor.SW_RESIZE_CURSOR
                        else -> Cursor.DEFAULT_CURSOR
                    }
                )
            }

            override fun mousePressed(e: MouseEvent) {
                edge = edgeAt(e.point)
                start = bounds; dragging = true
                startMouse = e.locationOnScreen
            }

            override fun mouseDragged(e: MouseEvent) {
                val s = start ?: return
                val m = startMouse ?: return
                if (edge == 0) return
                val dx = e.xOnScreen - m.x
                val dy = e.yOnScreen - m.y
                var (nx, ny, nw, nh) = listOf(s.x, s.y, s.width, s.height)
                if (edge and E != 0) nw = maxOf(minimumSize.width, s.width + dx)
                if (edge and S != 0) nh = maxOf(minimumSize.height, s.height + dy)
                if (edge and W != 0) { nw = maxOf(minimumSize.width, s.width - dx); nx = s.x + s.width - nw }
                if (edge and N != 0) { nh = maxOf(minimumSize.height, s.height - dy); ny = s.y + s.height - nh }
                setBounds(nx, ny, nw, nh)
                validate()
            }

            override fun mouseReleased(e: MouseEvent) { start = null; dragging = false }
        }
        root.addMouseListener(r)
        root.addMouseMotionListener(r)
    }

    companion object {
        const val EDGE = 5
        /** Transparent border around the paper for the shadow and tilted corners. */
        const val MARGIN = 12
        const val RADIUS = 18f

        /** Each note leans its own way, between 0.8 and 2.5 degrees. */
        fun tiltFor(id: String): Double {
            val h = Math.floorMod(id.hashCode(), 1000) / 1000.0
            val mag = 0.8 + h * 1.7
            return if (Math.floorMod(id.hashCode() / 1000, 2) == 0) mag else -mag
        }

        fun blend(a: Color, b: Color, t: Float) = Color(
            (a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt()
        )

        /** Soft drop shadow under the paper. */
        fun paintShadow(g: Graphics2D, r: Rectangle) {
            for (i in 1..8) {
                g.color = Color(0, 0, 0, 6)
                g.fill(RoundRectangle2D.Float(r.x + 1f - i, r.y + 4f - i, r.width + 2f * i, r.height + 2f * i, RADIUS + 2 * i, RADIUS + 2 * i))
            }
        }
        const val N = 1; const val S = 2; const val E = 4; const val W = 8

        fun styleText(t: JTextComponent) {
            t.isOpaque = false
            t.background = Color(0, 0, 0, 0)
            t.foreground = INK
            t.caretColor = INK
            t.selectionColor = Color(0x6D, 0x5D, 0x2A, 0x60)
            t.selectedTextColor = INK
            t.font = HAND
            if (t is JTextField) t.border = BorderFactory.createEmptyBorder(3, 2, 3, 2)
        }

        fun transparentScroll(c: JComponent) = JScrollPane(c).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            verticalScrollBar.preferredSize = Dimension(8, 0)
            verticalScrollBar.isOpaque = false
        }

        fun onChange(f: () -> Unit) = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = f()
            override fun removeUpdate(e: DocumentEvent) = f()
            override fun changedUpdate(e: DocumentEvent) = f()
        }

        /** Paints a grey hint inside an empty text field. */
        fun placeholder(field: JTextField, hint: String): JComponent = object : JPanel(BorderLayout()) {
            init { isOpaque = false; add(field) }
            override fun paintChildren(g: Graphics) {
                super.paintChildren(g)
                if (field.text.isEmpty()) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.color = MUTED
                    g2.font = HAND
                    g2.drawString(hint, field.x + field.insets.left, field.y + field.baselineOrZero())
                }
            }
        }.also { p -> field.document.addDocumentListener(onChange { p.repaint() }) }

        private fun JTextField.baselineOrZero(): Int {
            val b = getBaseline(width, height)
            return if (b > 0) b else height - 6
        }

        fun headerButton(text: String, tip: String, action: (Component) -> Unit) = JButton(text).apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            foreground = MUTED
            font = FONT.deriveFont(Font.BOLD, 15f)
            margin = java.awt.Insets(0, 6, 0, 6)
            toolTipText = tip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action(this) }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { foreground = INK }
                override fun mouseExited(e: MouseEvent) { foreground = MUTED }
            })
        }
    }
}

