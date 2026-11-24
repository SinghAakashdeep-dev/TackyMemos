package com.stickynotes.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.ContentScale
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stickynotes.app.EditNoteActivity
import com.stickynotes.app.INK
import com.stickynotes.app.INK_MUTED
import com.stickynotes.app.NOTE_COLORS
import com.stickynotes.app.Note
import com.stickynotes.app.NoteRepo
import com.stickynotes.app.isBoxLine
import com.stickynotes.app.isTickedLine
import com.stickynotes.app.toggleBoxLine

val NoteIdKey = ActionParameters.Key<String>("noteId")
val ItemIdKey = ActionParameters.Key<String>("itemId")

class NoteWidget : GlanceAppWidget() {
    // Exact size, so the tilted paper picture matches the widget's real size.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = NoteRepo.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetBindings.claimPendingIfUnbound(context, appWidgetId)
        val bindings = WidgetBindings.flow(context)
        provideContent {
            val notes by repo.notes.collectAsState()
            val map by bindings.collectAsState()
            val note = map[appWidgetId]?.let { notes[it] }
            WidgetContent(context, note, appWidgetId)
        }
    }
}

private val ink = ColorProvider(Color(INK))
private val muted = ColorProvider(Color(INK_MUTED))

@Composable
private fun WidgetContent(ctx: Context, note: Note?, appWidgetId: Int) {
    val bg = NOTE_COLORS[note?.color] ?: NOTE_COLORS.getValue("yellow")
    val size = LocalSize.current
    val paper = remember(size, bg, note?.id) { paperBitmap(ctx, size.width.value, size.height.value, bg.toInt(), tiltFor(note?.id ?: "")) }
    Box(GlanceModifier.fillMaxSize()) {
    Image(ImageProvider(paper), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    // Content stays inside the tilted paper's edges.
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (note == null || note.deleted) {
            Box(
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(ctx, appWidgetId))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (note?.deleted == true) "This note was deleted.\nTap to pick another." else "Tap to pick a note",
                    style = TextStyle(color = muted, fontSize = 13.sp),
                )
            }
            return@Column
        }
        val open = actionStartActivity(EditNoteActivity.intent(ctx, note.id))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title(),
                modifier = GlanceModifier.defaultWeight().clickable(open),
                style = TextStyle(color = muted, fontSize = 11.sp),
                maxLines = 1,
            )
            // "Tear off" a new note: creates one and asks the launcher to place a new widget for it.
            Text(
                "+",
                modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionStartActivity(TearOffActivity.intent(ctx, note))),
                style = TextStyle(color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
        }
        if (note.isChecklist) {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(note.items, itemId = { it.id.hashCode().toLong() }) { item ->
                    CheckBox(
                        checked = item.done,
                        onCheckedChange = actionRunCallback<ToggleItemAction>(
                            actionParametersOf(NoteIdKey to note.id, ItemIdKey to item.id)
                        ),
                        text = item.text,
                        style = hand(if (item.done) muted else ink, item.done),
                        maxLines = 2,
                    )
                }
                item {
                    Text(
                        "+  Add item",
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(actionStartActivity(EditNoteActivity.intent(ctx, note.id, addItem = true))),
                        style = TextStyle(color = muted, fontSize = 13.sp),
                    )
                }
            }
        } else if (note.text.lines().any { it.isBoxLine() }) {
            // Text note with "(*)" checkboxes: those lines become tickable rows.
            val lines = note.text.split('\n')
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(lines.indices.toList(), itemId = { it.toLong() }) { i ->
                    val line = lines[i]
                    if (line.isBoxLine()) {
                        val ticked = line.isTickedLine()
                        CheckBox(
                            checked = ticked,
                            onCheckedChange = actionRunCallback<ToggleLineAction>(
                                actionParametersOf(NoteIdKey to note.id, LineKey to i)
                            ),
                            text = line.trimStart().drop(1).trim(),
                            style = hand(if (ticked) muted else ink, ticked),
                            maxLines = 2,
                        )
                    } else {
                        Text(line.ifEmpty { " " }, modifier = GlanceModifier.fillMaxWidth().clickable(open), style = hand(ink))
                    }
                }
            }
        } else {
            Text(
                note.text.ifBlank { "Tap to write…" },
                modifier = GlanceModifier.fillMaxSize().clickable(open),
                style = hand(if (note.text.isBlank()) muted else ink),
            )
        }
    }
    }
}

/** Handwriting-style text (the system "cursive" font; widgets can't load bundled fonts). */
private fun hand(color: ColorProvider, struck: Boolean = false) = TextStyle(
    color = color,
    fontSize = 16.sp,
    fontFamily = FontFamily.Cursive,
    textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
)

val LineKey = ActionParameters.Key<Int>("line")

class ToggleLineAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val noteId = parameters[NoteIdKey] ?: return
        val line = parameters[LineKey] ?: return
        NoteRepo.get(context).update(noteId) { it.copy(text = toggleBoxLine(it.text, line)) }
    }
}

private fun configIntent(ctx: Context, appWidgetId: Int) =
    Intent(ctx, WidgetConfigActivity::class.java)
        .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .setData(Uri.parse("sticky://config/$appWidgetId"))

class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val noteId = parameters[NoteIdKey] ?: return
        val itemId = parameters[ItemIdKey] ?: return
        NoteRepo.get(context).update(noteId) { n ->
            n.copy(items = n.items.map { if (it.id == itemId) it.copy(done = !it.done) else it })
        }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetBindings.unbind(context, it) }
    }
}


/** Same lean as the desktop note: between 0.8 and 2.5 degrees, direction from the note id. */
fun tiltFor(id: String): Float {
    val h = Math.floorMod(id.hashCode(), 1000) / 1000f
    val mag = 0.8f + h * 1.7f
    return if (Math.floorMod(id.hashCode() / 1000, 2) == 0) mag else -mag
}

/** Draws the sticky-note paper: rounded, softly shaded, slightly rotated, with a drop shadow. */
fun paperBitmap(ctx: Context, wDp: Float, hDp: Float, argb: Int, angle: Float): Bitmap {
    // Keep the bitmap modest: widget bitmaps travel between processes.
    val scale = minOf(ctx.resources.displayMetrics.density, 2f)
    val w = (wDp * scale).toInt().coerceIn(48, 900)
    val h = (hDp * scale).toInt().coerceIn(48, 900)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val m = 9 * scale
    val rect = RectF(m, m, w - m, h - m)
    val r = 14 * scale
    c.rotate(angle, w / 2f, h / 2f)
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        setShadowLayer(5 * scale, 0f, 2.5f * scale, 0x40000000)
    }
    c.drawRoundRect(rect, r, r, shadow)
    val light = android.graphics.Color.argb(255,
        (android.graphics.Color.red(argb) + (255 - android.graphics.Color.red(argb)) * 0.25f).toInt(),
        (android.graphics.Color.green(argb) + (255 - android.graphics.Color.green(argb)) * 0.25f).toInt(),
        (android.graphics.Color.blue(argb) + (255 - android.graphics.Color.blue(argb)) * 0.25f).toInt())
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, rect.top, 0f, rect.bottom, light, argb, Shader.TileMode.CLAMP)
    }
    c.drawRoundRect(rect, r, r, fill)
    return bmp
}
