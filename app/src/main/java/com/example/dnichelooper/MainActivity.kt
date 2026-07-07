package com.example.dnichelooper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.dnichelooper.ui.TransportScreen
import com.example.dnichelooper.ui.TransportViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TransportViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val recordGranted = results[Manifest.permission.RECORD_AUDIO]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED)
            viewModel.onPermissionResult(recordGranted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A looper on stage should not dim/lock mid-performance.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TransportScreen(viewModel)
                }
            }
        }

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        val readAudioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, readAudioPermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += readAudioPermission
        }
        if (missing.isEmpty()) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // Reclaim the streams if another app (e.g. a tuner) took over the
        // USB interface while we were in the background.
        viewModel.retryStart()
    }
}
