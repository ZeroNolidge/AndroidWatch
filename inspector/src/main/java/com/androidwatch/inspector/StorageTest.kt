package com.androidwatch.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Finds out how much storage is real, the same way H2testw / F3 do for fake
 * USB sticks: fill the free space with data that can be regenerated, then read
 * it all back. Fake storage either runs out early or silently returns wrong
 * data for anything past the real capacity.
 */
class StorageTest(private val dir: File) {

    enum class Phase { WRITING, VERIFYING, DONE }

    data class Progress(
        val phase: Phase,
        val writtenMb: Long,
        val verifiedMb: Long = 0,
        val badMb: Long = 0,
        val mbPerSec: Double = 0.0,
        val reportedFreeMb: Long,
        val stoppedEarly: Boolean = false,
        val writeError: String? = null,
    )

    /** Stop writing and go straight to verifying what was written. */
    @Volatile var cancelled = false
    /** Abort verification too. */
    @Volatile var aborted = false

    fun run(onProgress: (Progress) -> Unit): Progress {
        dir.deleteRecursively()
        dir.mkdirs()
        val reportedFreeMb = dir.usableSpace / MB
        val block = ByteArray(MB.toInt())
        val readBack = ByteArray(MB.toInt())
        val blocksPerFile = mutableListOf<Int>()
        var written = 0L
        var writeError: String? = null
        val start = System.nanoTime()

        try {
            // ---- Write phase
            writing@ while (!cancelled) {
                val fileIndex = blocksPerFile.size
                var blocks = 0
                try {
                    FileOutputStream(File(dir, name(fileIndex))).use { out ->
                        while (blocks < BLOCKS_PER_FILE && !cancelled) {
                            if (dir.usableSpace < SAFETY_MARGIN) break
                            fill(block, fileIndex, blocks)
                            out.write(block)
                            blocks++
                            written++
                            if (written % 16 == 0L) {
                                val secs = (System.nanoTime() - start) / 1e9
                                onProgress(Progress(Phase.WRITING, written, mbPerSec = written / secs, reportedFreeMb = reportedFreeMb))
                            }
                        }
                        out.fd.sync() // push to flash so verification reads the disk, not the cache
                    }
                } catch (e: IOException) {
                    writeError = e.message ?: "write failed"
                }
                blocksPerFile += blocks
                if (writeError != null || blocks < BLOCKS_PER_FILE) break@writing
            }
            var stoppedEarly = cancelled

            // ---- Verify phase (runs even if writing was stopped by the user)
            var verified = 0L
            var bad = 0L
            verify@ for ((fileIndex, blocks) in blocksPerFile.withIndex()) {
                if (aborted) { stoppedEarly = true; break@verify }
                try {
                    FileInputStream(File(dir, name(fileIndex))).use { input ->
                        for (b in 0 until blocks) {
                            if (aborted) break
                            fill(block, fileIndex, b)
                            val ok = readFully(input, readBack) && readBack.contentEquals(block)
                            if (ok) verified++ else bad++
                            if ((verified + bad) % 16 == 0L) {
                                onProgress(Progress(Phase.VERIFYING, written, verified, bad, reportedFreeMb = reportedFreeMb, stoppedEarly = stoppedEarly, writeError = writeError))
                            }
                        }
                    }
                } catch (e: IOException) {
                    bad += blocks
                }
            }
            return Progress(Phase.DONE, written, verified, bad, reportedFreeMb = reportedFreeMb, stoppedEarly = stoppedEarly, writeError = writeError)
                .also(onProgress)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun readFully(input: FileInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    companion object {
        const val MB = 1024L * 1024
        private const val BLOCKS_PER_FILE = 256 // 256 MB files
        private const val SAFETY_MARGIN = 300 * MB // leave room so the watch keeps working

        private fun name(i: Int) = String.format(Locale.US, "fill_%05d.bin", i)

        /** Deterministic pseudo-random block content (xorshift64), unique per file+block. */
        fun fill(buf: ByteArray, fileIndex: Int, blockIndex: Int) {
            var x = (fileIndex.toLong() shl 32) xor blockIndex.toLong() xor -0x61c8864680b583ebL
            if (x == 0L) x = 1
            val bb = ByteBuffer.wrap(buf)
            while (bb.remaining() >= 8) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                bb.putLong(x)
            }
        }

        fun gb(mb: Long) = String.format(Locale.US, "%.1f GB", mb / 1024.0)

        fun verdict(p: Progress): Pair<String, Boolean> = when {
            p.badMb > 0 ->
                "FAKE STORAGE: ${gb(p.badMb)} of data came back corrupted. Only about " +
                    "${gb(p.verifiedMb)} is real. Don't store anything past that." to true
            p.stoppedEarly ->
                "Stopped after ${gb(p.writtenMb)}: all of it verified OK, but the test must fill " +
                    "the storage to catch fakes. Run it to the end to be sure." to false
            p.writeError != null && p.writtenMb < p.reportedFreeMb * 0.9 ->
                "FAKE STORAGE: the watch reported ${gb(p.reportedFreeMb)} free but ran out after " +
                    "${gb(p.writtenMb)} (${p.writeError})." to true
            else -> "Storage is real: ${gb(p.verifiedMb)} written and verified." to false
        }
    }
}

@Composable
fun StorageTestCard() {
    val context = LocalContext.current
    val view = LocalView.current
    var test by remember { mutableStateOf<StorageTest?>(null) }
    var progress by remember { mutableStateOf<StorageTest.Progress?>(null) }

    DisposableEffect(Unit) { onDispose { test?.let { it.cancelled = true; it.aborted = true } } }

    fun start() {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "storage-test")
        val t = StorageTest(dir)
        test = t
        progress = null
        view.keepScreenOn = true
        Thread {
            t.run { p -> view.post { progress = p } }
            view.post {
                view.keepScreenOn = false
                test = null
            }
        }.start()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text("Storage reality test", color = Color(0xFF90CAF9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        val p = progress
        val running = test != null
        when {
            p == null && !running -> Text(
                "Fills free space with test data, then reads it back to see how much is real. " +
                    "Can take hours on a big fake drive: keep the watch on its charger. " +
                    "Test files are deleted at the end.",
                color = Color.White, fontSize = 11.sp, lineHeight = 14.sp,
            )
            p == null -> Text("Starting…", color = Color.White, fontSize = 12.sp)
            p.phase == StorageTest.Phase.WRITING -> Text(
                "Writing ${StorageTest.gb(p.writtenMb)} of ~${StorageTest.gb(p.reportedFreeMb)} " +
                    "· ${String.format(Locale.US, "%.0f", p.mbPerSec)} MB/s",
                color = Color.White, fontSize = 12.sp,
            )
            p.phase == StorageTest.Phase.VERIFYING -> Text(
                "Verifying ${StorageTest.gb(p.verifiedMb + p.badMb)} of ${StorageTest.gb(p.writtenMb)}" +
                    (if (p.badMb > 0) " · ${StorageTest.gb(p.badMb)} BAD" else " · all good so far"),
                color = if (p.badMb > 0) Color(0xFFEF5350) else Color.White, fontSize = 12.sp,
            )
            else -> {
                val (text, bad) = StorageTest.verdict(p)
                Text(text, color = if (bad) Color(0xFFEF5350) else Color(0xFF66BB6A), fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Button(
                onClick = {
                    val t = test
                    when {
                        t == null -> start()
                        progress?.phase == StorageTest.Phase.VERIFYING -> t.aborted = true
                        else -> t.cancelled = true
                    }
                },
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFEF5350) else Color(0xFF1E88E5),
                ),
            ) {
                Text(
                    when {
                        !running -> if (p == null) "Start test" else "Run again"
                        p?.phase == StorageTest.Phase.VERIFYING -> "Stop"
                        else -> "Stop & verify"
                    },
                    fontSize = 12.sp,
                )
            }
        }
    }
}
