package com.example.filelink.webrtc

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.filelink.model.ConnectionState
import com.example.filelink.model.FileItem
import com.example.filelink.model.PeerItem
import com.example.filelink.model.TransferStatus
import com.example.filelink.network.SignalingClient
import com.example.filelink.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnectionFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RoomManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private var serverUrl: String = "wss://filesync.app/ws"
) {
    companion object {
        private const val TAG = "RoomManager"
    }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    val transferManager: TransferManager by lazy {
        TransferManager(context, scope, factory, serverUrl, object : TransferManager.TransferListener {
            override fun onProgress(fileId: String, progress: Float, speedText: String) {
                updateFileProgress(fileId, progress, speedText)
            }

            override fun onCompleted(fileId: String, uri: Uri?, filePath: String?) {
                updateFileCompleted(fileId, uri, filePath)
                scope.launch { _toastFlow.emit("Download completed!") }
            }

            override fun onFailed(fileId: String, error: String) {
                updateFileFailed(fileId, error)
                scope.launch { _toastFlow.emit("Transfer error: $error") }
            }
        })
    }

    var myUserId: String = generateUUID()
        private set
    var myUserName: String = generateRandomName()
        private set

    private var signalingClient: SignalingClient? = null
    private val activePeerConnections = ConcurrentHashMap<String, WebRtcConnection>()
    private var hostPeerId: String? = null
    private var roomPassword: String? = null

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerItem>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files = _files.asStateFlow()

    private val _passwordRequiredFlow = MutableSharedFlow<Unit>()
    val passwordRequiredFlow = _passwordRequiredFlow.asSharedFlow()

    private val _toastFlow = MutableSharedFlow<String>()
    val toastFlow = _toastFlow.asSharedFlow()

    fun updateServerUrl(url: String) {
        serverUrl = url
    }

    fun updateUserName(name: String) {
        val clean = name.trim().take(100)
        if (clean.isBlank()) return
        myUserName = clean

        // Update local files owner name
        _files.value = _files.value.map {
            if (it.ownerId == myUserId) it.copy(ownerName = myUserName) else it
        }

        // Notify peers
        val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
        if (isHost) {
            broadcastPeers()
        } else {
            hostPeerId?.let { hostId ->
                activePeerConnections[hostId]?.sendText(JSONObject().apply {
                    put("webrtc-user-name", JSONObject().apply {
                        put("id", myUserId)
                        put("name", myUserName)
                    })
                }.toString())
            }
        }
    }

    fun createRoom(password: String? = null) {
        roomPassword = password?.trim()?.ifEmpty { null }
        myUserId = generateUUID()
        _connectionState.value = ConnectionState.Connecting("Creating room...")

        initSignaling(myUserId) {
            _connectionState.value = ConnectionState.InRoom(
                roomId = myUserId,
                isHost = true,
                isSecured = roomPassword != null
            )
            _peers.value = listOf(PeerItem(id = myUserId, name = myUserName, isHost = true, isSelf = true))
        }
    }

    fun joinRoom(roomIdOrUrl: String, password: String? = null) {
        val targetRoomId = extractRoomId(roomIdOrUrl)
        if (targetRoomId.isBlank()) {
            scope.launch { _toastFlow.emit("Invalid room ID or URL") }
            return
        }

        hostPeerId = targetRoomId
        roomPassword = password?.trim()?.ifEmpty { null }
        myUserId = generateUUID()
        _connectionState.value = ConnectionState.Connecting("Connecting to room...")

        initSignaling(myUserId) {
            connectToHost(targetRoomId)
        }
    }

    private fun extractRoomId(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.contains("://")) {
            return trimmed.replace("/", "").take(64)
        }
        return try {
            val uri = Uri.parse(trimmed)
            val roomParam = uri.getQueryParameter("room")
            if (!roomParam.isNullOrBlank()) {
                roomParam.replace("/", "")
            } else {
                val path = uri.path ?: ""
                val segments = path.split("/").filter { it.isNotBlank() && !it.equals("Filelink-Sync", true) }
                segments.lastOrNull() ?: ""
            }
        } catch (_: Exception) {
            trimmed.replace("/", "")
        }
    }

    private fun initSignaling(peerId: String, onRegistered: () -> Unit) {
        signalingClient?.disconnect()
        signalingClient = SignalingClient(scope, serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "Signaling connected")
            }

            override fun onRegistered(peerId: String) {
                Log.d(TAG, "Signaling registered as: $peerId")
                onRegistered()
            }

            override fun onSignalReceived(from: String, payload: JSONObject) {
                handleSignal(from, payload)
            }

            override fun onPeerUnavailable(peerId: String) {
                Log.w(TAG, "Peer unavailable: $peerId")
                if (peerId == hostPeerId) {
                    _connectionState.value = ConnectionState.Disconnected
                    scope.launch { _toastFlow.emit("Host is unavailable or disconnected") }
                }
            }

            override fun onDisconnected(reason: String) {
                Log.w(TAG, "Signaling disconnected: $reason")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Signaling error: $error")
            }
        })
        signalingClient?.connect(peerId)
    }

    private fun connectToHost(targetHostId: String) {
        val conn = WebRtcConnection(
            remotePeerId = targetHostId,
            factory = factory,
            sendSignal = { to, payload -> signalingClient?.sendSignal(to, payload) },
            listener = createConnectionListener()
        )
        activePeerConnections[targetHostId] = conn
        conn.startAsOffer(label = "control", serialization = "binary")
    }

    private fun handleSignal(from: String, payload: JSONObject) {
        val kind = payload.optString("kind")
        val connId = payload.optString("connectionId")

        when (kind) {
            "offer" -> {
                val sdp = payload.optString("sdp")
                var conn = activePeerConnections[from]
                if (conn == null) {
                    conn = WebRtcConnection(
                        connectionId = connId,
                        remotePeerId = from,
                        factory = factory,
                        sendSignal = { to, p -> signalingClient?.sendSignal(to, p) },
                        listener = createConnectionListener()
                    )
                    activePeerConnections[from] = conn
                }
                conn.handleOffer(sdp)
            }
            "answer" -> {
                val sdp = payload.optString("sdp")
                activePeerConnections[from]?.handleAnswer(sdp)
            }
            "candidate" -> {
                val candObj = payload.optJSONObject("candidate")
                if (candObj != null) {
                    val cand = IceCandidate(
                        candObj.optString("sdpMid"),
                        candObj.optInt("sdpMLineIndex"),
                        candObj.optString("candidate")
                    )
                    activePeerConnections[from]?.addRemoteCandidate(cand)
                }
            }
            "close" -> {
                activePeerConnections[from]?.close()
                activePeerConnections.remove(from)
            }
        }
    }

    private fun createConnectionListener(): WebRtcConnection.Listener {
        return object : WebRtcConnection.Listener {
            override fun onChannelOpen(connection: WebRtcConnection) {
                Log.d(TAG, "Main DataChannel open with peer: ${connection.remotePeerId}")
                val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
                if (!isHost) {
                    // Send webrtc-connect to host
                    val hello = JSONObject().apply {
                        put("name", myUserName)
                        if (!roomPassword.isNullOrBlank()) {
                            put("password", hashPassword(roomPassword!!))
                        }
                    }
                    val msg = JSONObject().apply { put("webrtc-connect", hello) }
                    connection.sendText(msg.toString())
                }
            }

            override fun onTextMessage(connection: WebRtcConnection, text: String) {
                handleChannelTextMessage(connection, text)
            }

            override fun onBinaryMessage(connection: WebRtcConnection, data: ByteArray) {}
            override fun onBufferedAmountLow(connection: WebRtcConnection) {}

            override fun onChannelClosed(connection: WebRtcConnection) {
                Log.d(TAG, "Channel closed with: ${connection.remotePeerId}")
                activePeerConnections.remove(connection.remotePeerId)
                if (connection.remotePeerId == hostPeerId) {
                    _connectionState.value = ConnectionState.Disconnected
                    scope.launch { _toastFlow.emit("Disconnected from room") }
                } else {
                    val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
                    if (isHost) {
                        _peers.value = _peers.value.filter { it.id != connection.remotePeerId }
                        broadcastPeers()
                    }
                }
            }

            override fun onError(connection: WebRtcConnection, error: String) {
                Log.e(TAG, "Channel error: $error with ${connection.remotePeerId}")
            }
        }
    }

    private fun handleChannelTextMessage(conn: WebRtcConnection, text: String) {
        try {
            val json = JSONObject(text)
            val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true

            if (json.has("webrtc-connect") && isHost) {
                val hello = json.getJSONObject("webrtc-connect")
                val clientName = hello.optString("name", "Guest")
                val clientPass = hello.optString("password", "")

                if (!roomPassword.isNullOrBlank() && clientPass.isEmpty()) {
                    conn.sendText(JSONObject().apply {
                        put("webrtc-connect-response", JSONObject().apply { put("status", "password_required") })
                    }.toString())
                    return
                }
                if (!roomPassword.isNullOrBlank() && hashPassword(roomPassword!!) != clientPass) {
                    conn.sendText(JSONObject().apply {
                        put("webrtc-connect-response", JSONObject().apply { put("status", "password_invalid") })
                    }.toString())
                    return
                }

                // Accepted
                conn.sendText(JSONObject().apply {
                    put("webrtc-connect-response", JSONObject().apply {
                        put("status", "welcome")
                        put("secured", !roomPassword.isNullOrBlank())
                    })
                }.toString())

                // Add to peers
                val currentPeers = _peers.value.toMutableList()
                currentPeers.removeAll { it.id == conn.remotePeerId }
                currentPeers.add(PeerItem(id = conn.remotePeerId, name = clientName))
                _peers.value = currentPeers

                broadcastPeers()
                broadcastFiles()
            } else if (json.has("webrtc-connect-response") && !isHost) {
                val resp = json.getJSONObject("webrtc-connect-response")
                val status = resp.optString("status")
                val secured = resp.optBoolean("secured", false)

                when (status) {
                    "password_required", "password_invalid" -> {
                        scope.launch { _passwordRequiredFlow.emit(Unit) }
                    }
                    "welcome" -> {
                        _connectionState.value = ConnectionState.InRoom(
                            roomId = hostPeerId ?: "",
                            isHost = false,
                            isSecured = secured
                        )
                        scope.launch { _toastFlow.emit("Connected to room!") }
                    }
                }
            } else if (json.has("webrtc-peers")) {
                val peersArray = json.getJSONArray("webrtc-peers")
                val list = mutableListOf<PeerItem>()
                for (i in 0 until peersArray.length()) {
                    val p = peersArray.getJSONObject(i)
                    val id = p.optString("id")
                    val name = p.optString("name")
                    list.add(PeerItem(id = id, name = name, isHost = id == hostPeerId, isSelf = id == myUserId))
                }
                _peers.value = list

                if (json.has("webrtc-files")) {
                    parseFilesArray(json.getJSONArray("webrtc-files"))
                }
            } else if (json.has("webrtc-file-add")) {
                val addedArray = json.getJSONArray("webrtc-file-add")
                parseFilesArray(addedArray)
                if (isHost) {
                    // Host forwards to other peers
                    for (peer in _peers.value) {
                        if (peer.id != myUserId && peer.id != conn.remotePeerId) {
                            activePeerConnections[peer.id]?.sendText(text)
                        }
                    }
                }
            } else if (json.has("webrtc-file-remove")) {
                val removeObj = json.getJSONObject("webrtc-file-remove")
                val fileId = removeObj.optString("file_id")
                _files.value = _files.value.filter { it.id != fileId }
                if (isHost) {
                    for (peer in _peers.value) {
                        if (peer.id != myUserId && peer.id != conn.remotePeerId) {
                            activePeerConnections[peer.id]?.sendText(text)
                        }
                    }
                }
            } else if (json.has("webrtc-file-download")) {
                val dlObj = json.getJSONObject("webrtc-file-download")
                val fileId = dlObj.optString("file_id")
                val requesterId = dlObj.optString("requester_id")
                val filePeerId = dlObj.optString("peer_id")

                val targetFile = _files.value.find { it.id == fileId }
                if (targetFile != null) {
                    if (targetFile.ownerId == myUserId && targetFile.uri != null) {
                        Log.d(TAG, "Starting outbound send for file $fileId to filePeer $filePeerId")
                        updateFileTransferring(fileId)
                        transferManager.sendFile(fileId, targetFile.uri, targetFile.size, filePeerId)
                    } else if (isHost) {
                        // Forward request to owner
                        activePeerConnections[targetFile.ownerId]?.sendText(text)
                    }
                }
            } else if (json.has("webrtc-user-name") && isHost) {
                val nameObj = json.getJSONObject("webrtc-user-name")
                val id = nameObj.optString("id")
                val name = nameObj.optString("name")
                _peers.value = _peers.value.map { if (it.id == id) it.copy(name = name) else it }
                _files.value = _files.value.map { if (it.ownerId == id) it.copy(ownerName = name) else it }
                broadcastPeers()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling channel text frame", e)
        }
    }

    private fun parseFilesArray(arr: JSONArray) {
        val current = _files.value.toMutableList()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("id")
            val name = item.optString("name")
            val size = item.optLong("size", 0L)
            val ownerId = item.optString("owner_id")
            val ownerName = item.optString("owner_name")

            if (current.none { it.id == id }) {
                current.add(
                    FileItem(
                        id = id,
                        name = name,
                        size = size,
                        ownerId = ownerId,
                        ownerName = ownerName,
                        isLocal = ownerId == myUserId
                    )
                )
            }
        }
        _files.value = current
    }

    private fun broadcastPeers() {
        val arr = JSONArray()
        for (p in _peers.value) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
            })
        }
        val msg = JSONObject().apply {
            put("webrtc-peers", arr)
            put("webrtc-files", buildFilesJsonArray())
        }.toString()

        for (p in _peers.value) {
            if (p.id != myUserId) {
                activePeerConnections[p.id]?.sendText(msg)
            }
        }
    }

    private fun broadcastFiles() {
        val msg = JSONObject().apply {
            put("webrtc-files", buildFilesJsonArray())
        }.toString()
        for (p in _peers.value) {
            if (p.id != myUserId) {
                activePeerConnections[p.id]?.sendText(msg)
            }
        }
    }

    private fun buildFilesJsonArray(): JSONArray {
        val arr = JSONArray()
        for (f in _files.value) {
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("size", f.size)
                put("owner_id", f.ownerId)
                put("owner_name", f.ownerName)
            })
        }
        return arr
    }

    fun addFiles(newItems: List<FileItem>) {
        val current = _files.value.toMutableList()
        current.addAll(newItems)
        _files.value = current

        val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
        val arr = JSONArray()
        for (f in newItems) {
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("size", f.size)
                put("owner_id", f.ownerId)
                put("owner_name", f.ownerName)
            })
        }
        val msg = JSONObject().apply { put("webrtc-file-add", arr) }.toString()

        if (isHost) {
            for (p in _peers.value) {
                if (p.id != myUserId) activePeerConnections[p.id]?.sendText(msg)
            }
        } else {
            hostPeerId?.let { activePeerConnections[it]?.sendText(msg) }
        }
        scope.launch { _toastFlow.emit("${newItems.size} file(s) shared") }
    }

    fun removeFile(fileId: String) {
        _files.value = _files.value.filter { it.id != fileId }
        val msg = JSONObject().apply {
            put("webrtc-file-remove", JSONObject().apply {
                put("peer_id", myUserId)
                put("file_id", fileId)
            })
        }.toString()

        val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
        if (isHost) {
            for (p in _peers.value) {
                if (p.id != myUserId) activePeerConnections[p.id]?.sendText(msg)
            }
        } else {
            hostPeerId?.let { activePeerConnections[it]?.sendText(msg) }
        }
    }

    fun downloadFile(fileId: String) {
        val file = _files.value.find { it.id == fileId } ?: return
        updateFileStatus(fileId, TransferStatus.CONNECTING)

        transferManager.receiveFile(fileId, file.name, file.size) { filePeerId ->
            val reqMsg = JSONObject().apply {
                put("webrtc-file-download", JSONObject().apply {
                    put("file_id", fileId)
                    put("requester_id", myUserId)
                    put("requester_name", myUserName)
                    put("peer_id", filePeerId)
                })
            }.toString()

            val isHost = (_connectionState.value as? ConnectionState.InRoom)?.isHost == true
            val target = if (isHost) file.ownerId else hostPeerId
            target?.let { activePeerConnections[it]?.sendText(reqMsg) }
        }
    }

    fun cancelTransfer(fileId: String) {
        transferManager.cancelTransfer(fileId)
        updateFileStatus(fileId, TransferStatus.CANCELLED)
    }

    private fun updateFileStatus(fileId: String, status: TransferStatus) {
        _files.value = _files.value.map {
            if (it.id == fileId) it.copy(status = status) else it
        }
    }

    private fun updateFileTransferring(fileId: String) {
        _files.value = _files.value.map {
            if (it.id == fileId) it.copy(status = TransferStatus.TRANSFERRING) else it
        }
    }

    private fun updateFileProgress(fileId: String, progress: Float, speedText: String) {
        _files.value = _files.value.map {
            if (it.id == fileId) it.copy(
                progress = progress,
                speedText = speedText,
                status = TransferStatus.TRANSFERRING
            ) else it
        }
    }

    private fun updateFileCompleted(fileId: String, uri: Uri?, filePath: String?) {
        _files.value = _files.value.map {
            if (it.id == fileId) it.copy(
                progress = 1f,
                speedText = "",
                status = TransferStatus.COMPLETED,
                uri = uri ?: it.uri,
                localFilePath = filePath ?: it.localFilePath
            ) else it
        }
    }

    private fun updateFileFailed(fileId: String, error: String) {
        _files.value = _files.value.map {
            if (it.id == fileId) it.copy(
                status = TransferStatus.FAILED,
                errorMsg = error
            ) else it
        }
    }

    fun leaveRoom() {
        for (conn in activePeerConnections.values) {
            conn.close()
        }
        activePeerConnections.clear()
        signalingClient?.disconnect()
        signalingClient = null
        hostPeerId = null
        _connectionState.value = ConnectionState.Disconnected
        _peers.value = emptyList()
        _files.value = emptyList()
    }

    private fun generateUUID(): String = UUID.randomUUID().toString()

    private fun generateRandomName(): String {
        val adjectives = listOf("Swift", "Quick", "Clever", "Cosmic", "Lunar", "Solar", "Bright", "Nova", "Cyber", "Quantum")
        val nouns = listOf("Fox", "Falcon", "Eagle", "Wolf", "Otter", "Panda", "Lynx", "Tiger", "Dolphin", "Cheetah")
        return "${adjectives.random()} ${nouns.random()}"
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
