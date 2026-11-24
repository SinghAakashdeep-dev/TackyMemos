package com.stickynotes.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stickynotes.app.NOTE_COLORS
import com.stickynotes.app.Note
import com.stickynotes.app.NoteRepo

/** Shown when a widget is added from the widget picker (or tapped while empty): choose its note. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val repo = NoteRepo.get(this)

        fun choose(note: Note) {
            WidgetBindings.bind(this, appWidgetId, note.id)
            repo.refreshWidgets()
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val notes by repo.notes.collectAsState()
                    val live = notes.values.filter { !it.deleted }.sortedByDescending { it.updatedAt }
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Pick a note for this widget", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { choose(repo.create("text")) }) { Text("New note") }
                            OutlinedButton(onClick = { choose(repo.create("checklist")) }) { Text("New checklist") }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(live, key = { it.id }) { n ->
                                Text(
                                    preview(n),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(Color(NOTE_COLORS.getValue(n.color)))
                                        .clickable { choose(n) }.padding(12.dp),
                                    color = Color(0xFF3B3320),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun preview(n: Note): String = when {
    n.isChecklist -> n.items.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text }.ifBlank { "(empty checklist)" }
    else -> n.text.ifBlank { "(empty note)" }
}
