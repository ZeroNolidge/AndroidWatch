package com.androidwatch.launcher.claude

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidwatch.launcher.TextZoom
import com.androidwatch.launcher.WatchDensity
import com.androidwatch.launcher.ZoomButtons
import com.androidwatch.launcher.ZoomedText
import com.androidwatch.launcher.rememberHideKeyboard
import com.androidwatch.launcher.rememberKeyboardVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private val ClaudeOrange = Color(0xFFD97757)
private val Muted = Color(0xFF9E9E9E)
private val Bad = Color(0xFFEF5350)
private val Bubble = Color(0xFF1E1E1E)
private val MyBubble = Color(0xFF3A2A24)

/** Chat with Claude Code from the watch. Opened from the watch face's Claude bubble. */
class ClaudeActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val refresh = mutableIntStateOf(0)
    private var screen by mutableStateOf(Screen.CHAT)
    private var draft by mutableStateOf("")

    private enum class Screen { CHAT, PROJECTS, SETUP, MANUAL }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(WatchDensity.wrap(newBase))
    }

    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        addProject(text)
    }

    private val dictate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!words.isNullOrBlank()) draft = (draft.trimEnd() + " " + words).trim()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TextZoom.load(this)
        if (ClaudeProjects.current(this) == null) screen = Screen.SETUP
        setContent {
            refresh.intValue
            MaterialTheme(colorScheme = darkColorScheme()) {
                ZoomedText {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        when (screen) {
                            Screen.CHAT -> Chat()
                            Screen.PROJECTS -> Projects()
                            Screen.SETUP -> Setup()
                            Screen.MANUAL -> ManualEntry()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        refresh.intValue++
    }

    override fun onPause() {
        super.onPause()
        isVisible = false
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ actions

    private fun addProject(setupText: String) {
        try {
            val project = ClaudeProject.fromJson(JSONObject(setupText.trim()))
            ClaudeProjects.save(this, project)
            Toast.makeText(this, "Added ${project.name}", Toast.LENGTH_SHORT).show()
            screen = Screen.CHAT
            refresh.intValue++
        } catch (e: Exception) {
            val why = (e as? IllegalArgumentException)?.message ?: "That isn't a Watch Link setup code"
            Toast.makeText(this, why, Toast.LENGTH_LONG).show()
        }
    }

    private fun send(project: ClaudeProject, text: String) {
        val now = System.currentTimeMillis()
        val pending = ChatMessage("me-$now", fromMe = true, text = text, time = now, status = "sending")
        ClaudeInbox.upsert(this, project, listOf(pending))
        draft = ""
        refresh.intValue++
        io.execute {
            val updated = try {
                val r = ClaudeApi.fire(project, text)
                ClaudePollService.startPolling(this)
                pending.copy(status = "started", sessionUrl = r.sessionUrl)
            } catch (e: FireException) {
                pending.copy(status = e.message ?: "failed")
            }
            ClaudeInbox.upsert(this, project, listOf(updated))
            runOnUiThread { refresh.intValue++ }
        }
    }

    private fun startDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude…")
        try {
            dictate.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this, "No speech recognition on this watch. Install the Google app, or use your keyboard's mic key.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ------------------------------------------------------------ screens

    @Composable
    private fun Chat() {
        val project = ClaudeProjects.current(this) ?: return Setup()
        var messages by remember(project.id) { mutableStateOf(ClaudeInbox.messages(this, project)) }
        var error by remember(project.id) { mutableStateOf<String?>(null) }
        val listState = rememberLazyListState()

        // Refresh from GitHub while the chat is open.
        LaunchedEffect(project.id, refresh.intValue) {
            messages = ClaudeInbox.messages(this@ClaudeActivity, project)
            ClaudeInbox.markRead(this@ClaudeActivity, project)
            while (true) {
                error = withContext(Dispatchers.IO) {
                    runCatching { ClaudeInbox.refresh(this@ClaudeActivity, project); null }
                        .getOrElse { it.message ?: "Can't reach GitHub" }
                }
                messages = ClaudeInbox.messages(this@ClaudeActivity, project)
                ClaudeInbox.markRead(this@ClaudeActivity, project)
                delay(60_000) // GitHub allows 60 checks/hour without a token
            }
        }
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size) }

        val keyboardOpen = rememberKeyboardVisible()
        val hideKeyboard = rememberHideKeyboard()
        val sendDraft = {
            if (draft.isNotBlank()) {
                hideKeyboard()
                send(project, draft.trim())
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            if (keyboardOpen) {
                // The keyboard covers most of a watch screen: show only the text being typed.
                Spacer(Modifier.weight(1f))
            } else {
                // Header: project (tap to switch) + text size.
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "✳ ${project.name} ▾",
                        color = ClaudeOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { screen = Screen.PROJECTS },
                    )
                    ZoomButtons(this@ClaudeActivity)
                }
                error?.let { Text(it, color = Bad, fontSize = 10.sp) }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                "Ask Claude to do something in ${project.name}. Replies appear here, and the watch " +
                                    "buzzes when one arrives (you can leave this screen).",
                                color = Muted, fontSize = 11.sp, lineHeight = 14.sp,
                            )
                        }
                    }
                    items(messages, key = { it.id }) { MessageBubble(project, it) }
                }
            }

            // Input row. While typing: ⌄ hides the keyboard, ➤ sends (so does the keyboard's Send key).
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (keyboardOpen) {
                    RoundKey("⌄", color = Color(0xFF444444)) { hideKeyboard() }
                    Spacer(Modifier.width(4.dp))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Ask Claude…", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = if (keyboardOpen) 2 else 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                if (!keyboardOpen) {
                    RoundKey("🎤") { startDictation() }
                    Spacer(Modifier.width(4.dp))
                }
                RoundKey("➤", enabled = draft.isNotBlank()) { sendDraft() }
            }
        }
    }

    @Composable
    private fun MessageBubble(project: ClaudeProject, m: ChatMessage) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start) {
            Column(
                Modifier
                    .padding(start = if (m.fromMe) 24.dp else 0.dp, end = if (m.fromMe) 0.dp else 24.dp)
                    .background(if (m.fromMe) MyBubble else Bubble, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                Text(m.text, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp)
                val status = when {
                    !m.fromMe -> "Claude · $time"
                    m.status == "sending" -> "Sending…"
                    m.status == "started" -> "Started · $time · open session ↗"
                    else -> "Not sent: ${m.status} · tap to retry"
                }
                val failed = m.fromMe && m.status != "sending" && m.status != "started"
                Text(
                    status,
                    color = if (failed) Bad else Muted, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp).clickable(enabled = m.fromMe) {
                        when {
                            m.sessionUrl != null -> open(m.sessionUrl)
                            failed -> send(project, m.text)
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun Projects() {
        BackHandler { screen = Screen.CHAT }
        val projects = ClaudeProjects.all(this)
        val current = ClaudeProjects.current(this)
        LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Projects", color = ClaudeOrange, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            items(projects, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Bubble, RoundedCornerShape(10.dp))
                        .clickable {
                            ClaudeProjects.setCurrent(this@ClaudeActivity, p)
                            screen = Screen.CHAT
                            refresh.intValue++
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (p.id == current?.id) "● " else "") + p.name, color = Color.White, fontSize = 13.sp)
                        Text("${p.owner}/${p.repo} #${p.issue}", color = Muted, fontSize = 10.sp)
                    }
                    Text("✕", color = Muted, fontSize = 16.sp, modifier = Modifier.clickable {
                        ClaudeProjects.remove(this@ClaudeActivity, p)
                        refresh.intValue++
                        if (ClaudeProjects.all(this@ClaudeActivity).isEmpty()) screen = Screen.SETUP
                    }.padding(6.dp))
                }
            }
            item { WideButton("+ Add a project") { screen = Screen.SETUP } }
        }
    }

    @Composable
    private fun Setup() {
        BackHandler(enabled = ClaudeProjects.current(this) != null) { screen = Screen.CHAT }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text("Set up Claude", color = ClaudeOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "On your phone, open Watch Link → \"Claude for your watch\" and follow the steps. " +
                    "It ends with a QR code: scan it here.",
                color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            WideButton("Scan setup QR code") {
                scanQr.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the QR code from Watch Link")
                        .setBeepEnabled(false),
                )
            }
            Spacer(Modifier.height(6.dp))
            WideButton("Type it in instead", primary = false) { screen = Screen.MANUAL }
            if (ClaudeProjects.current(this@ClaudeActivity) != null) {
                Spacer(Modifier.height(6.dp))
                WideButton("Back to chat", primary = false) { screen = Screen.CHAT }
            }
        }
    }

    @Composable
    private fun ManualEntry() {
        BackHandler { screen = Screen.SETUP }
        var name by remember { mutableStateOf("") }
        var fire by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var inbox by remember { mutableStateOf("") }
        var gh by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Add project by hand", color = ClaudeOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Field("Name", name) { name = it }
            Field("Routine Fire URL or trig_ ID", fire) { fire = it }
            Field("Routine token (sk-ant-oat01-…)", token) { token = it }
            Field("Inbox issue link", inbox) { inbox = it }
            Field("GitHub token (private repos only)", gh) { gh = it }
            WideButton("Save") {
                val routine = Regex("trig_[A-Za-z0-9]+").find(fire)?.value ?: fire.trim()
                addProject(
                    JSONObject().put("name", name).put("routine", routine).put("token", token)
                        .put("inbox", inbox).put("gh", gh).toString(),
                )
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange,
            label = { Text(label, fontSize = 10.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun RoundKey(label: String, enabled: Boolean = true, color: Color = ClaudeOrange, onClick: () -> Unit) {
        Box(
            Modifier
                .size(40.dp)
                .background(if (enabled) color else Color(0xFF333333), RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 16.sp) }
    }

    @Composable
    private fun WideButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (primary) ClaudeOrange else Color(0xFF333333)),
        ) { Text(label, fontSize = 13.sp) }
    }

    companion object {
        /** True while the chat is on screen (no notifications needed then). */
        @Volatile var isVisible = false
            private set
    }
}
