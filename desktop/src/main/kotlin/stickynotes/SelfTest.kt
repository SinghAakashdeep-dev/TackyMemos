package stickynotes

import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * STICKY_SELFTEST=<output folder> runs a scripted check against the real windows, without touching the
 * user's mouse or keyboard: events are dispatched straight to the components. Writes report.txt and a
 * PNG of every note window, then exits. Use with STICKY_PROFILE=test so real notes aren't touched.
 */
object SelfTest {
    private val log = StringBuilder()
    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        log.append(if (ok) "PASS " else "FAIL ").append(name).append(if (detail.isEmpty()) "" else "  ($detail)").append('\n')
        if (!ok) failures++
    }

    private fun ui(f: () -> Unit) {
        SwingUtilities.invokeAndWait(f)
        Thread.sleep(250)
    }

    private fun key(c: Component, code: Int, mods: Int = 0) {
        val ch = if (code == KeyEvent.VK_ENTER) '\n' else KeyEvent.CHAR_UNDEFINED
        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, code, ch))
    }

    fun run(app: App, out: File) {
        out.mkdirs()
        try {
            Thread.sleep(800)
            val store = app.store
            var first: NoteWindow? = null
            ui { first = app.windows.values.first() }
            val w1 = first!!
            check("welcome note opened", app.windows.size == 1 && store.live().size == 1)

            // Tear off a checklist from the first note.
            ui { app.tearOff(w1, "checklist") }
            val w2 = app.windows.values.last()
            val id2 = w2.noteId
            check("tear-off created a second window", app.windows.size == 2 && store.get(id2)?.isChecklist == true)
            check("torn-off note inherits color", store.get(id2)?.color == store.get(w1.noteId)?.color)

            // Add three items through the "Add item" field + Enter.
            for (t in listOf("Buy milk", "Call mom", "Finish ER diagram")) ui {
                w2.addField.text = t
                key(w2.addField, KeyEvent.VK_ENTER)
            }
            check("three items added", store.get(id2)?.items?.map { it.text } == listOf("Buy milk", "Call mom", "Finish ER diagram"),
                store.get(id2)?.items?.joinToString { it.text } ?: "")

            // Tick the second item.
            ui { w2.itemChecks[1].doClick() }
            check("item ticked", store.get(id2)?.items?.get(1)?.done == true)

            // Enter in the middle of an item splits it.
            ui {
                val f = w2.itemFields[0]
                f.caretPosition = 3
                key(f, KeyEvent.VK_ENTER)
            }
            check("Enter splits an item", store.get(id2)?.items?.take(2)?.map { it.text } == listOf("Buy", " milk"),
                store.get(id2)?.items?.joinToString { it.text } ?: "")
            // Backspace at the start merges it back.
            ui {
                val f = w2.itemFields[1]
                f.caretPosition = 0
                key(f, KeyEvent.VK_BACK_SPACE)
            }
            check("Backspace merges items", store.get(id2)?.items?.first()?.text == "Buy milk" && store.get(id2)?.items?.size == 3)

            // Text note: type, then convert to a checklist and back.
            ui { app.tearOff(w1, "text") }
            val w3 = app.windows.values.last()
            ui { w3.textAreaForTest.text = "- eggs\n- bread\n\n- jam" }
            Thread.sleep(700) // debounce save
            check("text typed is saved", store.get(w3.noteId)?.text == "- eggs\n- bread\n\n- jam")
            ui { app.convert(w3.noteId) }
            check("convert text → checklist", store.get(w3.noteId)?.items?.map { it.text } == listOf("eggs", "bread", "jam"))
            ui { app.setColor(w3.noteId, "blue") }
            check("color change", store.get(w3.noteId)?.color == "blue")

            // "(*)" typed in a text note becomes a tickable checkbox.
            ui { app.tearOff(w1, "text") }
            val w5 = app.windows.values.last()
            ui {
                val doc = w5.textAreaForTest.document
                for (s in listOf("Groceries\n", "(*)", "milk\n", "(*)", " eggs")) doc.insertString(doc.length, s, null)
            }
            Thread.sleep(700)
            check("(*) becomes ☐", w5.plainText() == "Groceries\n☐ milk\n☐ eggs", w5.plainText().replace("\n", "\\n"))
            ui { w5.toggleBox(w5.plainText().indexOf('☐')) }
            check("clicking ☐ ticks it", store.get(w5.noteId)?.text == "Groceries\n☑ milk\n☐ eggs", store.get(w5.noteId)?.text?.replace("\n", "\\n") ?: "")
            ui { app.convert(w5.noteId) }
            check("convert keeps ticked state", store.get(w5.noteId)?.items?.map { it.text to it.done } ==
                listOf("Groceries" to false, "milk" to true, "eggs" to false), store.get(w5.noteId)?.items.toString())
            ui { app.convert(w5.noteId) }
            check("convert back gives ☐/☑ lines", store.get(w5.noteId)?.text == "☐ Groceries\n☑ milk\n☐ eggs", store.get(w5.noteId)?.text?.replace("\n", "\\n") ?: "")
            ui { w5.setLocation(600, 300) }

            // Positions are recorded.
            ui { w2.setLocation(300, 300) }
            Thread.sleep(800)
            check("moved window position saved", store.layout[id2]?.let { it.x == 300 && it.y == 300 } == true, store.layout[id2].toString())

            // Always on top toggles every window.
            ui { app.setAlwaysOnTop(true) }
            check("always on top", app.windows.values.all { it.isAlwaysOnTop })
            ui { app.setAlwaysOnTop(false) }

            // Let the resting notes lean (pointer elsewhere), then picture every window off-screen.
            Thread.sleep(2500)
            ui {
                app.windows.values.forEachIndexed { i, w ->
                    val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
                    img.createGraphics().apply { color = java.awt.Color(0xE8E8E8); fillRect(0, 0, w.width, w.height); dispose() }
                    val g = img.createGraphics()
                    w.contentPane.paint(g)
                    g.dispose()
                    ImageIO.write(img, "png", File(out, "note-$i.png"))
                }
            }

            // Delete (an empty note needs no confirmation).
            ui { app.tearOff(null, "text") }
            val w4 = app.windows.values.last()
            ui { app.delete(w4.noteId, w4) }
            check("delete removes window and marks note deleted", !app.windows.containsKey(w4.noteId) && store.get(w4.noteId)?.deleted == true)

            ui { store.saveNow() }
            check("dirty set tracks unsynced notes", store.dirty.size >= 4, store.dirty.size.toString())
        } catch (e: Throwable) {
            check("self-test crashed", false, e.toString())
        }
        log.append(if (failures == 0) "ALL PASSED\n" else "$failures FAILED\n")
        File(out, "report.txt").writeText(log.toString())
        exitProcess(if (failures == 0) 0 else 1)
    }
}
