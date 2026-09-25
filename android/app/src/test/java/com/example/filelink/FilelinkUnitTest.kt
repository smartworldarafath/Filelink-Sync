package com.example.filelink

import com.example.filelink.model.FileItem
import com.example.filelink.model.TransferStatus
import com.example.filelink.util.FileStorageHelper
import com.example.filelink.util.QrUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilelinkUnitTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", FileItem.formatFileSize(0))
        assertEquals("1.00 KB", FileItem.formatFileSize(1024))
        assertEquals("1.00 MB", FileItem.formatFileSize(1024 * 1024))
        assertEquals("500.00 MB", FileItem.formatFileSize(500L * 1024 * 1024))
        assertEquals("2.50 GB", FileItem.formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testSanitizeFilename() {
        assertEquals("photo.jpg", FileStorageHelper.sanitizeFilename("photo.jpg"))
        assertEquals("document.pdf", FileStorageHelper.sanitizeFilename("/path/to/my/document.pdf"))
        assertEquals("video.mp4", FileStorageHelper.sanitizeFilename("C:\\Users\\Downloads\\video.mp4"))
        assertTrue(FileStorageHelper.sanitizeFilename("..").startsWith("file_"))
        assertTrue(FileStorageHelper.sanitizeFilename("").startsWith("file_"))
    }

    @Test
    fun testFileItemDefaults() {
        val item = FileItem(
            id = "test-id",
            name = "archive.zip",
            size = 1048576L,
            ownerId = "owner-1",
            ownerName = "Alice"
        )
        assertEquals("1.00 MB", item.formattedSize)
        assertEquals(TransferStatus.IDLE, item.status)
        assertEquals(0f, item.progress)
    }
}
