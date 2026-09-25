package com.example.filelink.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.filelink.model.ConnectionState
import com.example.filelink.model.FileItem
import com.example.filelink.model.PeerItem
import com.example.filelink.model.TransferStatus
import com.example.filelink.util.FileStorageHelper
import com.example.filelink.util.QrUtils
import com.example.filelink.webrtc.RoomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FilelinkViewModel(application: Application) : AndroidViewModel(application) {

    private val roomManager = RoomManager(application.applicationContext, viewModelScope)

    val connectionState = roomManager.connectionState
    val peers = roomManager.peers
    val files = roomManager.files

    private val _myUserName = MutableStateFlow(roomManager.myUserName)
    val myUserName = _myUserName.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap = _qrBitmap.asStateFlow()

    private val _showPasswordDialog = MutableStateFlow(false)
    val showPasswordDialog = _showPasswordDialog.asStateFlow()

    private val _showQrDialog = MutableStateFlow(false)
    val showQrDialog = _showQrDialog.asStateFlow()

    private val _showNameDialog = MutableStateFlow(false)
    val showNameDialog = _showNameDialog.asStateFlow()

    private val _showScanner = MutableStateFlow(false)
    val showScanner = _showScanner.asStateFlow()

    private val _pendingJoinTarget = MutableStateFlow<String?>(null)

    val toastFlow = roomManager.toastFlow

    init {
        viewModelScope.launch {
            roomManager.passwordRequiredFlow.collect {
                _showPasswordDialog.value = true
            }
        }
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.InRoom) {
                    val roomUrl = buildRoomUrl(state.roomId)
                    withContext(Dispatchers.Default) {
                        _qrBitmap.value = QrUtils.generateQrBitmap(roomUrl)
                    }
                } else {
                    _qrBitmap.value = null
                }
            }
        }
    }

    fun buildRoomUrl(roomId: String): String {
        return "https://smartworldarafath.github.io/Filelink-Sync/?room=$roomId"
    }

    fun createRoom(password: String?) {
        roomManager.createRoom(password)
    }

    fun joinRoom(target: String, password: String? = null) {
        _pendingJoinTarget.value = target
        roomManager.joinRoom(target, password)
    }

    fun submitPassword(password: String) {
        _showPasswordDialog.value = false
        val target = _pendingJoinTarget.value
        if (!target.isNullOrBlank()) {
            roomManager.joinRoom(target, password)
        }
    }

    fun dismissPasswordDialog() {
        _showPasswordDialog.value = false
        _pendingJoinTarget.value = null
        leaveRoom()
    }

    fun leaveRoom() {
        roomManager.leaveRoom()
    }

    fun updateUserName(name: String) {
        roomManager.updateUserName(name)
        _myUserName.value = roomManager.myUserName
        _showNameDialog.value = false
    }

    fun toggleQrDialog(show: Boolean) {
        _showQrDialog.value = show
    }

    fun toggleScanner(show: Boolean) {
        _showScanner.value = show
    }

    fun toggleNameDialog(show: Boolean) {
        _showNameDialog.value = show
    }

    fun addFilesFromUris(uris: List<Uri>, contentResolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            val newFiles = mutableListOf<FileItem>()
            for (uri in uris) {
                var fileName = "file_${System.currentTimeMillis()}"
                var fileSize = 0L

                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                            if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                newFiles.add(
                    FileItem(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        size = fileSize,
                        ownerId = roomManager.myUserId,
                        ownerName = roomManager.myUserName,
                        uri = uri,
                        isLocal = true,
                        status = TransferStatus.IDLE
                    )
                )
            }
            withContext(Dispatchers.Main) {
                roomManager.addFiles(newFiles)
            }
        }
    }

    fun removeFile(fileId: String) {
        roomManager.removeFile(fileId)
    }

    fun downloadFile(fileId: String) {
        roomManager.downloadFile(fileId)
    }

    fun cancelTransfer(fileId: String) {
        roomManager.cancelTransfer(fileId)
    }

    fun downloadAllFiles() {
        val nonLocalFiles = files.value.filter { !it.isLocal && it.status != TransferStatus.COMPLETED }
        viewModelScope.launch {
            for (file in nonLocalFiles) {
                roomManager.downloadFile(file.id)
            }
        }
    }

    fun openFile(context: Context, fileItem: FileItem) {
        FileStorageHelper.openFile(context, fileItem.uri, fileItem.localFilePath)
    }
}
