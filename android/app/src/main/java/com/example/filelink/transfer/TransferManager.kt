package com.example.filelink.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.filelink.network.SignalingClient
import com.example.filelink.util.FileStorageHelper
import com.example.filelink.webrtc.WebRtcConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnectionFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val factory: PeerConnectionFactory,
    private val serverUrl: String = "wss://filesync.app/ws",
    private val listener: TransferListener
) {
    companion object {
        private const val TAG = "TransferManager"
        private const val CHUNK_SIZE = 16 * 1024 // 16 KiB
        private const val HIGH_WATER_MARK = 1_000_000L // 1 MB buffer limit
        private const val REPORT_INTERVAL_BYTES = 256 * 1024L // 256 KB report frequency
    }

    interface TransferListener {
        fun onProgress(fileId: String, progress: Float, speedText: String)
        fun onCompleted(fileId: String, uri: Uri?, filePath: String?)
        fun onFailed(fileId: String, error: String)
    }

    private val activeConnections = ConcurrentHashMap<String, WebRtcConnection>()
    private val activeSignalingClients = ConcurrentHashMap<String, SignalingClient>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun sendFile(
        fileId: String,
        uri: Uri,
        fileSize: Long,
        targetFilePeerId: String
    ) {
        val txPeerId = "file_tx_" + UUID.randomUUID().toString().replace("-", "").take(12)
        var connection: WebRtcConnection? = null

        val signaling = SignalingClient(scope, serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {}

            override fun onRegistered(peerId: String) {
                Log.d(TAG, "Sender transfer registered as $peerId, connecting to $targetFilePeerId")
                val conn = WebRtcConnection(
                    remotePeerId = targetFilePeerId,
                    factory = factory,
                    sendSignal = { to, payload ->
                        activeSignalingClients[txPeerId]?.sendSignal(to, payload)
                    },
                    listener = object : WebRtcConnection.Listener {
                        override fun onChannelOpen(connection: WebRtcConnection) {
                            Log.d(TAG, "Sender DataChannel open! Starting stream for file $fileId")
                            val job = scope.launch(Dispatchers.IO) {
                                streamFileOutbound(fileId, uri, fileSize, connection)
                            }
                            activeJobs[fileId] = job
                        }

                        override fun onTextMessage(connection: WebRtcConnection, text: String) {
                            try {
                                val json = JSONObject(text)
                                when (json.optString("type")) {
                                    "progress" -> {
                                        val percent = json.optInt("percent", 0)
                                        listener.onProgress(fileId, percent / 100f, "")
                                    }
                                    "abort" -> {
                                        Log.w(TAG, "Receiver aborted download for file $fileId")
                                        cancelTransfer(fileId)
                                        listener.onFailed(fileId, "Receiver stopped transfer")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling message on sender", e)
                            }
                        }

                        override fun onBinaryMessage(connection: WebRtcConnection, data: ByteArray) {}
                        override fun onBufferedAmountLow(connection: WebRtcConnection) {}
                        override fun onChannelClosed(connection: WebRtcConnection) {
                            cleanup(fileId, txPeerId)
                        }

                        override fun onError(connection: WebRtcConnection, error: String) {
                            Log.e(TAG, "Sender connection error: $error")
                            listener.onFailed(fileId, error)
                            cleanup(fileId, txPeerId)
                        }
                    }
                )
                connection = conn
                activeConnections[fileId] = conn
                conn.startAsOffer(label = "transfer", serialization = "raw")
            }

            override fun onSignalReceived(from: String, payload: JSONObject) {
                when (payload.optString("kind")) {
                    "answer" -> {
                        val sdp = payload.optString("sdp")
                        connection?.handleAnswer(sdp)
                    }
                    "candidate" -> {
                        val candObj = payload.optJSONObject("candidate")
                        if (candObj != null) {
                            val cand = IceCandidate(
                                candObj.optString("sdpMid"),
                                candObj.optInt("sdpMLineIndex"),
                                candObj.optString("candidate")
                            )
                            connection?.addRemoteCandidate(cand)
                        }
                    }
                    "close" -> {
                        connection?.close()
                    }
                }
            }

            override fun onPeerUnavailable(peerId: String) {
                listener.onFailed(fileId, "Target peer unavailable")
                cleanup(fileId, txPeerId)
            }

            override fun onDisconnected(reason: String) {}
            override fun onError(error: String) {}
        })

        activeSignalingClients[txPeerId] = signaling
        signaling.connect(txPeerId)
    }

    private suspend fun streamFileOutbound(
        fileId: String,
        uri: Uri,
        fileSize: Long,
        conn: WebRtcConnection
    ) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                listener.onFailed(fileId, "Cannot open file input stream")
                conn.close()
                return
            }

            // Send header
            val header = JSONObject().apply {
                put("type", "header")
                put("size", fileSize)
            }
            conn.sendText(header.toString())

            val buffer = ByteArray(CHUNK_SIZE)
            var totalSent = 0L
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = 0L

            while (totalSent < fileSize) {
                val bytesToRead = minOf(CHUNK_SIZE.toLong(), fileSize - totalSent).toInt()
                val read = inputStream.read(buffer, 0, bytesToRead)
                if (read <= 0) break

                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                // Backpressure handling: wait while buffer is high
                while (conn.bufferedAmount >= HIGH_WATER_MARK) {
                    delay(8)
                }

                if (!conn.sendBinary(chunk)) {
                    listener.onFailed(fileId, "Failed to send chunk")
                    return
                }

                totalSent += read

                val now = System.currentTimeMillis()
                if (now - lastReportTime >= 100 || totalSent == fileSize) {
                    val timeDiff = (now - lastReportTime) / 1000f
                    val bytesDiff = totalSent - lastReportBytes
                    val speed = if (timeDiff > 0) bytesDiff / timeDiff else 0f
                    val speedText = formatSpeed(speed)
                    val progress = if (fileSize > 0) totalSent.toFloat() / fileSize else 0f
                    listener.onProgress(fileId, progress, speedText)
                    lastReportTime = now
                    lastReportBytes = totalSent
                }
            }

            // Await buffer drain then send end marker
            while (conn.bufferedAmount > 0) {
                delay(10)
            }
            conn.sendText(JSONObject().apply { put("type", "end") }.toString())
            delay(200)

            listener.onProgress(fileId, 1f, "")
            listener.onCompleted(fileId, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Outbound file streaming failed", e)
            listener.onFailed(fileId, e.localizedMessage ?: "Streaming failed")
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    fun receiveFile(
        fileId: String,
        fileName: String,
        fileSize: Long,
        onFilePeerReady: (filePeerId: String) -> Unit
    ) {
        val rxPeerId = "file_rx_" + UUID.randomUUID().toString().replace("-", "").take(12)
        var connection: WebRtcConnection? = null
        var outputDest: FileStorageHelper.OutputDestination? = null
        var outputStream: OutputStream? = null
        var totalReceived = 0L
        var lastReportBytes = 0L
        var lastReportTime = System.currentTimeMillis()
        var lastSenderReportBytes = 0L

        val signaling = SignalingClient(scope, serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {}

            override fun onRegistered(peerId: String) {
                Log.d(TAG, "Receiver transfer registered as $peerId")
                onFilePeerReady(peerId)
            }

            override fun onSignalReceived(from: String, payload: JSONObject) {
                when (payload.optString("kind")) {
                    "offer" -> {
                        val sdp = payload.optString("sdp")
                        val connId = payload.optString("connectionId")
                        val conn = WebRtcConnection(
                            connectionId = connId,
                            remotePeerId = from,
                            factory = factory,
                            sendSignal = { to, signalPayload ->
                                activeSignalingClients[rxPeerId]?.sendSignal(to, signalPayload)
                            },
                            listener = object : WebRtcConnection.Listener {
                                override fun onChannelOpen(connection: WebRtcConnection) {
                                    Log.d(TAG, "Receiver DataChannel open for $fileId")
                                }

                                override fun onTextMessage(connection: WebRtcConnection, text: String) {
                                    try {
                                        val json = JSONObject(text)
                                        when (json.optString("type")) {
                                            "header" -> {
                                                val declaredSize = json.optLong("size", fileSize)
                                                Log.d(TAG, "Received transfer header: $declaredSize bytes")
                                                outputDest = FileStorageHelper.createDownloadOutputStream(context, fileName)
                                                outputStream = outputDest?.outputStream
                                                if (outputStream == null) {
                                                    listener.onFailed(fileId, "Failed to create destination file")
                                                    connection.sendText(JSONObject().apply {
                                                        put("type", "abort")
                                                        put("reason", "no-sink")
                                                    }.toString())
                                                    connection.close()
                                                }
                                                totalReceived = 0L
                                                lastReportTime = System.currentTimeMillis()
                                            }
                                            "end" -> {
                                                Log.d(TAG, "Transfer end marker received. Total: $totalReceived / $fileSize")
                                                try {
                                                    outputStream?.flush()
                                                    outputStream?.close()
                                                    outputStream = null
                                                } catch (_: Exception) {}

                                                if (totalReceived >= fileSize) {
                                                    listener.onProgress(fileId, 1f, "")
                                                    listener.onCompleted(fileId, outputDest?.publicUri, outputDest?.absolutePath)
                                                } else {
                                                    listener.onFailed(fileId, "Incomplete transfer ($totalReceived / $fileSize bytes)")
                                                }
                                                cleanup(fileId, rxPeerId)
                                            }
                                            "cancel" -> {
                                                Log.w(TAG, "Sender cancelled transfer")
                                                listener.onFailed(fileId, "Sender cancelled transfer")
                                                cleanup(fileId, rxPeerId)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error parsing incoming text frame", e)
                                    }
                                }

                                override fun onBinaryMessage(connection: WebRtcConnection, data: ByteArray) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            outputStream?.write(data)
                                            totalReceived += data.size

                                            val now = System.currentTimeMillis()
                                            // Send progress report back to sender
                                            if (totalReceived == fileSize || totalReceived - lastSenderReportBytes >= REPORT_INTERVAL_BYTES) {
                                                lastSenderReportBytes = totalReceived
                                                val percent = if (fileSize > 0) ((totalReceived.toDouble() / fileSize) * 100).toInt() else 0
                                                connection.sendText(JSONObject().apply {
                                                    put("type", "progress")
                                                    put("percent", percent)
                                                }.toString())
                                            }

                                            // Throttled UI progress update
                                            if (now - lastReportTime >= 100 || totalReceived == fileSize) {
                                                val timeDiff = (now - lastReportTime) / 1000f
                                                val bytesDiff = totalReceived - lastReportBytes
                                                val speed = if (timeDiff > 0) bytesDiff / timeDiff else 0f
                                                val speedText = formatSpeed(speed)
                                                val progress = if (fileSize > 0) totalReceived.toFloat() / fileSize else 0f
                                                listener.onProgress(fileId, progress, speedText)
                                                lastReportTime = now
                                                lastReportBytes = totalReceived
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error writing file chunk", e)
                                            listener.onFailed(fileId, "Write error: ${e.message}")
                                            connection.sendText(JSONObject().apply {
                                                put("type", "abort")
                                                put("reason", "write-failed")
                                            }.toString())
                                            cleanup(fileId, rxPeerId)
                                        }
                                    }
                                }

                                override fun onBufferedAmountLow(connection: WebRtcConnection) {}
                                override fun onChannelClosed(connection: WebRtcConnection) {
                                    cleanup(fileId, rxPeerId)
                                }

                                override fun onError(connection: WebRtcConnection, error: String) {
                                    Log.e(TAG, "Receiver connection error: $error")
                                    listener.onFailed(fileId, error)
                                    cleanup(fileId, rxPeerId)
                                }
                            }
                        )
                        connection = conn
                        activeConnections[fileId] = conn
                        conn.handleOffer(sdp)
                    }
                    "candidate" -> {
                        val candObj = payload.optJSONObject("candidate")
                        if (candObj != null) {
                            val cand = IceCandidate(
                                candObj.optString("sdpMid"),
                                candObj.optInt("sdpMLineIndex"),
                                candObj.optString("candidate")
                            )
                            connection?.addRemoteCandidate(cand)
                        }
                    }
                    "close" -> {
                        connection?.close()
                    }
                }
            }

            override fun onPeerUnavailable(peerId: String) {
                listener.onFailed(fileId, "Sender unavailable")
                cleanup(fileId, rxPeerId)
            }

            override fun onDisconnected(reason: String) {}
            override fun onError(error: String) {}
        })

        activeSignalingClients[rxPeerId] = signaling
        signaling.connect(rxPeerId)
    }

    fun cancelTransfer(fileId: String) {
        activeJobs[fileId]?.cancel()
        activeJobs.remove(fileId)
        activeConnections[fileId]?.let { conn ->
            try {
                conn.sendText(JSONObject().apply {
                    put("type", "abort")
                    put("reason", "user-cancelled")
                }.toString())
            } catch (_: Exception) {}
            conn.close()
        }
        activeConnections.remove(fileId)
    }

    private fun cleanup(fileId: String, transferPeerId: String) {
        activeJobs[fileId]?.cancel()
        activeJobs.remove(fileId)
        activeConnections.remove(fileId)
        activeSignalingClients[transferPeerId]?.disconnect()
        activeSignalingClients.remove(transferPeerId)
    }

    private fun formatSpeed(bytesPerSec: Float): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}
