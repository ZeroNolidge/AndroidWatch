package com.androidwatch.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException

/** What the phone UI shows. Written from background threads; Compose state is thread-safe for this. */
object LinkState {
    val status = mutableStateOf("Not running: grant notification access")
    val watchName = mutableStateOf<String?>(null)
}

/**
 * Waits for the watch to connect over Bluetooth (one watch at a time) and
 * passes each received JSON line to [onMessage].
 */
class RfcommServer(
    private val adapter: BluetoothAdapter,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var writer: BufferedWriter? = null

    val isConnected get() = client != null

    fun start() {
        if (running) return
        running = true
        Thread(::loop, "link-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        closeQuietly(serverSocket)
        closeQuietly(client)
    }

    @SuppressLint("MissingPermission") // Checked at runtime: SecurityException is handled below.
    private fun loop() {
        while (running) {
            try {
                if (!adapter.isEnabled) {
                    LinkState.status.value = "Bluetooth is off"
                    Thread.sleep(5000)
                    continue
                }
                val ss = adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
                serverSocket = ss
                LinkState.status.value = "Waiting for the watch to connect…"
                val socket = ss.accept()
                closeQuietly(ss)
                handle(socket)
            } catch (_: SecurityException) {
                LinkState.status.value = "Bluetooth permission missing"
                sleepQuietly(5000)
            } catch (_: InterruptedException) {
                return
            } catch (e: IOException) {
                if (running) {
                    LinkState.status.value = "Bluetooth error, retrying… (${e.message})"
                    sleepQuietly(3000)
                }
            }
        }
        LinkState.status.value = "Stopped"
        LinkState.watchName.value = null
    }

    @SuppressLint("MissingPermission")
    private fun handle(socket: BluetoothSocket) {
        client = socket
        synchronized(this) { writer = socket.outputStream.bufferedWriter() }
        val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: "watch"
        LinkState.watchName.value = name
        LinkState.status.value = "Connected to $name"
        try {
            onConnected()
            socket.inputStream.bufferedReader().forEachLine { line ->
                runCatching { onMessage(JSONObject(line)) }
            }
        } catch (_: IOException) {
            // Watch went away; fall through and wait again.
        } finally {
            synchronized(this) { writer = null }
            closeQuietly(socket)
            client = null
            LinkState.watchName.value = null
        }
    }

    /** Thread-safe. Returns false if no watch is connected. */
    fun send(msg: JSONObject): Boolean = synchronized(this) {
        val w = writer ?: return false
        try {
            w.write(msg.toString())
            w.write("\n")
            w.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun closeQuietly(c: Closeable?) = runCatching { c?.close() }
    private fun sleepQuietly(ms: Long) = runCatching { Thread.sleep(ms) }
}
