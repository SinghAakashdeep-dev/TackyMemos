package com.stickynotes.app.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.stickynotes.app.EditNoteActivity
import com.stickynotes.app.Note
import com.stickynotes.app.NoteRepo

/**
 * Invisible activity behind the widget's "+" button. Creates a new note and asks the launcher to
 * pin a new widget for it, so the user can drop it anywhere on the home screen.
 */
class TearOffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val note = NoteRepo.get(this).create(
            type = intent.getStringExtra(EXTRA_TYPE) ?: "text",
            color = intent.getStringExtra(EXTRA_COLOR) ?: "yellow",
        )
        if (!requestPin(this, note.id)) {
            Toast.makeText(this, "Long-press the home screen → Widgets → Tacky Memos to place it", Toast.LENGTH_LONG).show()
            startActivity(EditNoteActivity.intent(this, note.id))
        }
        finish()
    }

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_COLOR = "color"

        fun intent(ctx: Context, from: Note) = Intent(ctx, TearOffActivity::class.java)
            .putExtra(EXTRA_TYPE, "text")
            .putExtra(EXTRA_COLOR, from.color)
            .setData(Uri.parse("sticky://tearoff/${from.id}"))

        /** Returns false if this launcher can't pin widgets. */
        fun requestPin(ctx: Context, noteId: String): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            WidgetBindings.setPending(ctx, noteId)
            val callback = PendingIntent.getBroadcast(
                ctx,
                noteId.hashCode(),
                Intent(ctx, PinnedReceiver::class.java).putExtra("noteId", noteId),
                // Must be mutable: the launcher adds EXTRA_APPWIDGET_ID to it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            return mgr.requestPinAppWidget(ComponentName(ctx, NoteWidgetReceiver::class.java), null, callback)
        }
    }
}

/** Called by the launcher once the new widget is placed. */
class PinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val noteId = intent.getStringExtra("noteId") ?: return
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        WidgetBindings.bind(context, appWidgetId, noteId)
        WidgetBindings.clearPending(context)
        NoteRepo.get(context).refreshWidgets()
    }
}
