package com.example.filelink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.filelink.theme.FilelinkTheme
import com.example.filelink.ui.FilelinkViewModel
import com.example.filelink.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: FilelinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            FilelinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val roomParam = data.getQueryParameter("room")
        if (!roomParam.isNullOrBlank()) {
            viewModel.joinRoom(roomParam)
        } else {
            val path = data.path ?: ""
            val segments = path.split("/").filter { it.isNotBlank() && !it.equals("Filelink-Sync", true) }
            val lastSegment = segments.lastOrNull()
            if (!lastSegment.isNullOrBlank()) {
                viewModel.joinRoom(lastSegment)
            }
        }
    }
}
