package com.stickynotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Full editor for one note; opened by tapping a widget. */
class EditNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val addItem = intent.getBooleanExtra(EXTRA_ADD, false)
        setContent { MaterialTheme { NoteEditor(NoteRepo.get(this), id, addItem, onClose = ::finish) } }
    }

    companion object {
        private const val EXTRA_ID = "noteId"
        private const val EXTRA_ADD = "addItem"

        fun intent(ctx: Context, noteId: String, addItem: Boolean = false) =
            Intent(ctx, EditNoteActivity::class.java)
                .putExtra(EXTRA_ID, noteId)
                .putExtra(EXTRA_ADD, addItem)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("sticky://edit/$noteId?add=$addItem"))
    }
}

private val Ink = Color(INK)
private val Muted = Color(INK_MUTED)

@Composable
fun NoteEditor(repo: NoteRepo, id: String, focusAdd: Boolean, onClose: () -> Unit) {
    val notes by repo.notes.collectAsState()
    val note = notes[id]
    if (note == null || note.deleted) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val bg = Color(NOTE_COLORS.getValue(note.color))

    Column(Modifier.fillMaxSize().background(bg).systemBarsPadding().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NOTE_COLORS.forEach { (name, c) ->
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                        .border(if (name == note.color) 2.dp else 1.dp, if (name == note.color) Ink else Muted, CircleShape)
                        .clickable { repo.update(id) { it.copy(color = name) } }
                )
            }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { repo.update(id, ::convert) }) {
                Text(if (note.isChecklist) "Make text note" else "Make checklist", color = Ink)
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Ink) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done", color = Ink) }
        }
        if (note.isChecklist) ChecklistEditor(repo, note, focusAdd) else TextEditor(repo, note)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It will also be removed from your laptop.") },
            confirmButton = { TextButton(onClick = { repo.delete(id); onClose() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun convert(n: Note): Note =
    if (n.isChecklist) n.copy(type = "text", text = n.items.joinToString("\n") { (if (it.done) "$TICKED " else "$BOX ") + it.text }, items = emptyList())
    else n.copy(
        type = "checklist",
        text = "",
        items = n.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { l -> Item(text = l.trimStart('-', '•', '*', BOX, TICKED).trim(), done = l.startsWith(TICKED)) }
            .filter { it.text.isNotEmpty() },
    )

// Handwriting-style font (Android's built-in "cursive" family, same as the widget).
private val bodyStyle = TextStyle(color = Ink, fontSize = 19.sp, lineHeight = 26.sp, fontFamily = FontFamily.Cursive)

/** Strikes through and greys out lines that start with ☑. */
private object TickedLines : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildAnnotatedString {
            append(s)
            var start = 0
            for (line in s.split('\n')) {
                if (line.isTickedLine()) addStyle(SpanStyle(color = Muted, textDecoration = TextDecoration.LineThrough), start, start + line.length)
                start += line.length + 1
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

@Composable
private fun TextEditor(repo: NoteRepo, note: Note) {
    // Local state so typing isn't interrupted; saved after a short pause.
    var value by remember(note.id) { mutableStateOf(TextFieldValue(note.text)) }
    LaunchedEffect(value.text) {
        if (value.text == note.text) return@LaunchedEffect
        delay(500)
        repo.update(note.id) { it.copy(text = value.text) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (note.text.isEmpty()) focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            value = when {
                // "(*)" typed: turn it into a checkbox.
                new.text.contains(BOX_SHORTCUT) -> {
                    val (t, c) = expandBoxShortcuts(new.text, new.selection.start)
                    TextFieldValue(t, TextRange(c))
                }
                // Tapped right on a ☐/☑ (text unchanged, cursor moved onto it): tick or untick.
                new.text == value.text && new.selection.collapsed && new.selection != value.selection -> {
                    val at = new.selection.start
                    val hit = listOf(at, at - 1).firstOrNull { new.text.getOrNull(it) == BOX || new.text.getOrNull(it) == TICKED }
                    if (hit == null) new
                    else {
                        val line = new.text.substring(0, hit).count { it == '\n' }
                        TextFieldValue(toggleBoxLine(new.text, line), TextRange(hit + 2))
                    }
                }
                else -> new
            }
        },
        textStyle = bodyStyle,
        cursorBrush = SolidColor(Ink),
        visualTransformation = TickedLines,
        modifier = Modifier.fillMaxSize().padding(4.dp).focusRequester(focus),
        decorationBox = { inner ->
            if (value.text.isEmpty()) Text("Write something…  (type (*) for a checkbox)", style = bodyStyle.copy(color = Muted))
            inner()
        },
    )
}

@Composable
private fun ChecklistEditor(repo: NoteRepo, note: Note, focusAdd: Boolean) {
    var newItem by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusAdd || note.items.isEmpty()) addFocus.requestFocus() }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(note.items, key = { _, it -> it.id }) { _, item ->
            var text by remember(item.id) { mutableStateOf(item.text) }
            LaunchedEffect(text) {
                if (text == item.text) return@LaunchedEffect
                delay(500)
                repo.update(note.id) { n -> n.copy(items = n.items.map { if (it.id == item.id) it.copy(text = text) else it }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = { c ->
                        repo.update(note.id) { n -> n.copy(items = n.items.map { if (it.id == item.id) it.copy(done = c) else it }) }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = Ink, uncheckedColor = Muted),
                )
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = bodyStyle.copy(
                        color = if (item.done) Muted else Ink,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(Ink),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    repo.update(note.id) { n -> n.copy(items = n.items.filterNot { it.id == item.id }) }
                }) { Text("✕", color = Muted) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, top = 6.dp)) {
                Text("+", style = bodyStyle.copy(color = Muted), modifier = Modifier.padding(end = 14.dp))
                BasicTextField(
                    value = newItem,
                    onValueChange = { newItem = it },
                    singleLine = true,
                    textStyle = bodyStyle,
                    cursorBrush = SolidColor(Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val t = newItem.trim()
                        if (t.isNotEmpty()) {
                            repo.update(note.id) { it.copy(items = it.items + Item(text = t)) }
                            newItem = ""
                        }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(addFocus),
                    decorationBox = { inner ->
                        if (newItem.isEmpty()) Text("Add item", style = bodyStyle.copy(color = Muted))
                        inner()
                    },
                )
            }
        }
    }
}
