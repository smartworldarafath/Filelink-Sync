package com.example.filelink.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val scope: CoroutineScope,
    private val serverUrl: String = "wss://filesync.app/ws",
    private val listener: Listener
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val PING_INTERVAL_MS = 10_000L
    }

    interface Listener {
        fun onConnected()
        fun onRegistered(peerId: String)
        fun onSignalReceived(from: String, payload: JSONObject)
        fun onPeerUnavailable(peerId: String)
        fun onDisconnected(reason: String)
        fun onError(error: String)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var registeredPeerId: String? = null
    private var isClosedManually = false

    fun connect(peerId: String) {
        registeredPeerId = peerId
        isClosedManually = false
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected, registering peer: $peerId")
                listener.onConnected()
                val regMsg = JSONObject().apply {
                    put("type", "register")
                    put("id", peerId)
                }
                webSocket.send(regMsg.toString())
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "registered" -> {
                            val id = json.optString("id")
                            Log.d(TAG, "Registered as: $id")
                            listener.onRegistered(id)
                        }
                        "signal" -> {
                            val from = json.optString("from")
                            val payload = json.optJSONObject("payload")
                            if (payload != null && from.isNotBlank()) {
                                listener.onSignalReceived(from, payload)
                            }
                        }
                        "peer-unavailable" -> {
                            val unavailableId = json.optString("id")
                            Log.w(TAG, "Peer unavailable: $unavailableId")
                            listener.onPeerUnavailable(unavailableId)
                        }
                        "pong" -> {
                            // keepalive ack
                        }
                        "error" -> {
                            val message = json.optString("message", "Signaling error")
                            Log.e(TAG, "Signaling server reported error: $message")
                            listener.onError(message)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                stopPingLoop()
                if (!isClosedManually) {
                    listener.onDisconnected(reason.ifEmpty { "Connection closed" })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                stopPingLoop()
                if (!isClosedManually) {
                    listener.onError(t.localizedMessage ?: "WebSocket failure")
                    listener.onDisconnected(t.localizedMessage ?: "Connection failure")
                }
            }
        })
    }

    fun sendSignal(to: String, payload: JSONObject) {
        val msg = JSONObject().apply {
            put("type", "signal")
            put("to", to)
            put("payload", payload)
        }
        val text = msg.toString()
        webSocket?.send(text)
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                try {
                    val ping = JSONObject().apply { put("type", "ping") }
                    webSocket?.send(ping.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send ping", e)
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    fun disconnect() {
        isClosedManually = true
        stopPingLoop()
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing websocket", e)
        }
        webSocket = null
    }
}
