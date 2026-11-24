package stickynotes

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Sign in / create account / sign out for sync. */
class AccountDialog(private val app: App) : JFrame("Tacky Memos sync") {
    private val panel = JPanel()
    private val message = JLabel(" ")

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = false
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(16, 20, 16, 20)
        panel.background = Color(0xFFFBE0)
        contentPane = panel
        render()
    }

    private fun render() {
        panel.removeAll()
        val s = app.sync
        when {
            !s.configured -> {
                put(h("Sync isn't set up"))
                put(p("<html>Add firebase-config.json (see README), then restart Tacky Memos.</html>"))
            }
            s.email != null -> {
                put(h("Sync is on"))
                put(p("<html>Signed in as ${s.email}.<br>Notes sync automatically.</html>"))
                put(JButton("Sign out").apply { addActionListener { app.sync.signOut(); app.refreshTray(); render() } })
            }
            else -> {
                put(h("Sync your notes"))
                put(p("<html>Use the same email and password here and in the Android app.</html>"))
                val email = JTextField(22)
                val pw = JPasswordField(22)
                put(label("Email")); put(email)
                put(label("Password")); put(pw)
                val signIn = JButton("Sign in")
                val signUp = JButton("Create account")
                put(Box.createVerticalStrut(12))
                put(JPanel(GridLayout(1, 2, 8, 0)).apply { isOpaque = false; add(signIn); add(signUp) })
                fun go(create: Boolean) {
                    signIn.isEnabled = false; signUp.isEnabled = false
                    show("Working…", false)
                    val e = email.text.trim()
                    val p = String(pw.password)
                    thread {
                        val err = app.sync.signIn(e, p, create)
                        SwingUtilities.invokeLater {
                            signIn.isEnabled = true; signUp.isEnabled = true
                            if (err != null) show(err, true) else { app.refreshTray(); render() }
                        }
                    }
                }
                signIn.addActionListener { go(false) }
                signUp.addActionListener { go(true) }
                rootPane.defaultButton = signIn
            }
        }
        put(Box.createVerticalStrut(8))
        put(message)
        pack()
        minimumSize = Dimension(340, 0)
        setLocationRelativeTo(null)
        panel.revalidate()
        panel.repaint()
    }

    private fun put(c: java.awt.Component) {
        (c as? JComponent)?.alignmentX = LEFT_ALIGNMENT
        if (c is JTextField) c.maximumSize = Dimension(Int.MAX_VALUE, c.preferredSize.height)
        panel.add(c)
    }

    private fun show(text: String, error: Boolean) {
        message.text = "<html>$text</html>"
        message.foreground = if (error) Color(0xB3261E) else Color.DARK_GRAY
        pack()
    }

    private fun h(t: String) = JLabel(t).apply { font = FONT.deriveFont(Font.BOLD, 17f); border = BorderFactory.createEmptyBorder(0, 0, 4, 0) }
    private fun p(t: String) = JLabel(t).apply { font = FONT.deriveFont(12.5f); foreground = Color.DARK_GRAY; border = BorderFactory.createEmptyBorder(0, 0, 10, 0) }
    private fun label(t: String) = JLabel(t).apply { font = FONT.deriveFont(12f); border = BorderFactory.createEmptyBorder(8, 0, 3, 0) }
}
