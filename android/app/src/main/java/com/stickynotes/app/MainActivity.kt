package com.stickynotes.app

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.stickynotes.app.widget.TearOffActivity
import com.stickynotes.app.widget.preview
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Home() } } }
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val repo = remember { NoteRepo.get(ctx) }
    val notes by repo.notes.collectAsState()
    val live = notes.values.filter { !it.deleted }.sortedByDescending { it.updatedAt }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Tacky Memos", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        item { AccountCard() }
        item {
            Text(
                "Long-press your home screen → Widgets → Tacky Memos to place a note. " +
                    "On a widget, tap + to tear off a new one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pinNew(ctx, repo.create("text")) }) { Text("New note") }
                OutlinedButton(onClick = { pinNew(ctx, repo.create("checklist")) }) { Text("New checklist") }
            }
        }
        items(live, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(NOTE_COLORS.getValue(n.color)))
                    .clickable { ctx.startActivity(EditNoteActivity.intent(ctx, n.id)) }.padding(12.dp)
            ) {
                Text(preview(n), maxLines = 4, overflow = TextOverflow.Ellipsis, color = Color(INK))
                TextButton(onClick = { pinNew(ctx, n, created = false) }) { Text("Add to home screen", color = Color(INK)) }
            }
        }
    }
}

private fun pinNew(ctx: android.content.Context, note: Note, created: Boolean = true) {
    if (!TearOffActivity.requestPin(ctx, note.id)) {
        Toast.makeText(ctx, "Your launcher can't pin widgets; add one from the widget list.", Toast.LENGTH_LONG).show()
        if (created) ctx.startActivity(EditNoteActivity.intent(ctx, note.id))
    }
}

@Composable
private fun AccountCard() {
    if (!StickyApp.firebaseReady) {
        Card { Text("Sync is off: this build has no google-services.json (see README).", Modifier.padding(16.dp)) }
        return
    }
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(Unit) {
        val l = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(l)
        onDispose { auth.removeAuthStateListener(l) }
    }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (e: FirebaseAuthException) {
                message = e.localizedMessage
            } catch (e: Exception) {
                message = e.localizedMessage ?: e.toString()
            }
            busy = false
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val u = user
            if (u != null) {
                Text("Syncing as ${u.email}")
                OutlinedButton(onClick = { auth.signOut() }) { Text("Sign out") }
            } else {
                Text("Sign in with the same account as on your laptop.")
                OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { run { auth.signInWithEmailAndPassword(email, password).await() } }) { Text("Sign in") }
                    OutlinedButton(enabled = !busy, onClick = { run { auth.createUserWithEmailAndPassword(email, password).await() } }) { Text("Create account") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
