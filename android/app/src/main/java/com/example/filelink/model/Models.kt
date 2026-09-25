package com.example.filelink.model

import android.net.Uri

enum class TransferStatus {
    IDLE,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class PeerItem(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val isSelf: Boolean = false
)

data class FileItem(
    val id: String,
    val name: String,
    val size: Long,
    val ownerId: String,
    val ownerName: String,
    val uri: Uri? = null,
    val isLocal: Boolean = false,
    val progress: Float = 0f,
    val speedText: String = "",
    val status: TransferStatus = TransferStatus.IDLE,
    val errorMsg: String? = null,
    val localFilePath: String? = null
) {
    val formattedSize: String
        get() = formatFileSize(size)

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val clamped = digitGroups.coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, clamped.toDouble())
            return String.format("%.2f %s", value, units[clamped])
        }
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val message: String = "Connecting...") : ConnectionState
    data class InRoom(
        val roomId: String,
        val isHost: Boolean,
        val isSecured: Boolean = false
    ) : ConnectionState
}
