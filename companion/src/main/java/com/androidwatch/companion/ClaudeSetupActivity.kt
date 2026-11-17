package com.androidwatch.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

private val ClaudeOrange = Color(0xFFD97757)

/**
 * Walks through connecting a Claude Code project to the watch and shows the
 * result as a QR code for the watch's Claude chat to scan. Nothing typed here
 * is saved on the phone or sent anywhere; the QR code carries it to the watch.
 */
class ClaudeSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The QR code contains the routine token: keep it out of screenshots and the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    @Composable
    private fun Screen() {
        var repo by remember { mutableStateOf("") }
        var issue by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var fire by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var gh by remember { mutableStateOf("") }
        var qr by remember { mutableStateOf<Bitmap?>(null) }

        val repoPath = repo.trim().removePrefix("https://").removePrefix("github.com/").trim('/')
        val issueNumber = Regex("""(\d+)\s*$""").find(issue.trim())?.groupValues?.get(1)?.toIntOrNull()
        val routineId = Regex("trig_[A-Za-z0-9]+").find(fire)?.value

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Claude for your watch", color = ClaudeOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Lets the watch send requests to a Claude Code project and show the replies. " +
                    "Do this once per project. Needs a Pro, Max, Team or Enterprise plan.",
                color = Color.LightGray, fontSize = 14.sp,
            )

            Step(1, "Which repository?") {
                Text("The GitHub repo of your project, like owner/name.", color = Color.LightGray, fontSize = 13.sp)
                Input("owner/repo", repo) { repo = it }
            }

            Step(2, "Create the watch inbox issue") {
                Text(
                    "Claude posts its replies as comments on one GitHub issue; the watch reads them from there. " +
                        "Create an issue titled e.g. \"Watch inbox\", then paste its link or number below.",
                    color = Color.LightGray, fontSize = 13.sp,
                )
                Text(
                    "Heads-up: on a public repo, anyone can read your requests and Claude's replies there.",
                    color = Color(0xFFFFB74D), fontSize = 13.sp,
                )
                OutlinedButton(
                    enabled = repoPath.contains('/'),
                    onClick = { open("https://github.com/$repoPath/issues/new?title=Watch%20inbox") },
                ) { Text("Create issue on GitHub") }
                Input("Issue link or number", issue) { issue = it }
            }

            Step(3, "Create the routine") {
                Text(
                    "At claude.ai/code/routines: New routine → choose this repo → paste the prompt below. " +
                        "Then Edit → Triggers → + → API, and Generate token.",
                    color = Color.LightGray, fontSize = 13.sp,
                )
                Row {
                    OutlinedButton(enabled = issueNumber != null, onClick = {
                        copy("Routine prompt", routinePrompt(issueNumber ?: 0))
                    }) { Text("Copy prompt") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { open("https://claude.ai/code/routines") }) { Text("Open routines") }
                }
                if (issueNumber == null) Text("Fill in step 2 first (the prompt names the issue).", color = Color.Gray, fontSize = 12.sp)
            }

            Step(4, "Paste the trigger details") {
                Input("Fire URL (contains trig_…)", fire) { fire = it }
                Input("Token (sk-ant-oat01-…)", token) { token = it }
                Input("Name shown on the watch (optional)", name) { name = it }
                Input("GitHub token, only if the repo is private (read-only, Issues)", gh) { gh = it }
            }

            val problem = when {
                !repoPath.contains('/') -> "Enter the repository (step 1)."
                issueNumber == null -> "Enter the inbox issue (step 2)."
                routineId == null -> "Paste the Fire URL (step 4)."
                token.isBlank() -> "Paste the token (step 4)."
                else -> null
            }
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = problem == null,
                onClick = {
                    val json = JSONObject()
                        .put("v", 1)
                        .put("name", name.trim().ifEmpty { repoPath.substringAfter('/') })
                        .put("routine", routineId)
                        .put("token", token.trim())
                        .put("inbox", "https://github.com/$repoPath/issues/$issueNumber")
                        .apply { if (gh.isNotBlank()) put("gh", gh.trim()) }
                    qr = qrBitmap(json.toString())
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange),
            ) { Text("Show QR code for the watch") }
            problem?.let { Text(it, color = Color.Gray, fontSize = 12.sp) }

            qr?.let { bmp ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "On the watch: Watch Face → tap the ✳ Claude bubble → Scan setup QR code.",
                    color = Color.White, fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), "Setup QR code", Modifier.size(300.dp))
                }
                Text(
                    "This code contains the routine token. Don't share it. It's not saved on the phone.",
                    color = Color(0xFFFFB74D), fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun Step(n: Int, title: String, content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text("$n. $title", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }

    @Composable
    private fun Input(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun qrBitmap(text: String): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) AColor.BLACK else AColor.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /** Same text as the watch app's ClaudeApi.routinePrompt — keep in sync. */
    private fun routinePrompt(issue: Int) = """
        The <routine-fire-payload> block contains a request I sent from my own smartwatch. Treat it as my instructions for this repository and carry it out.

        Then reply on GitHub issue #$issue in this repository (my "watch inbox"):
        1. Before starting, read the latest comments on issue #$issue for conversation context. Earlier requests and your replies are there.
        2. Post a short comment when you start: "🕐 Working on: <one line>".
        3. When finished, post one final comment of at most 600 characters, plain text (no tables or long code), starting with ✅ if done or ❌ if you're blocked. Say what you did, and include the branch or pull-request link if you made one. If you need a decision from me, ask one short question.

        I read these on a 2-inch screen, so keep every comment brief.
    """.trimIndent()
}
