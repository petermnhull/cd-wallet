package com.peterhull.shelf

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CdWalletApp(vm, onLogin = { vm.auth.startLogin(this) }) }
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (vm.loggedIn.value) vm.player.connect(this)
        // Connecting a controller starts PlaybackService; it keeps itself alive while music plays.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
    }

    override fun onStop() {
        super.onStop()
        controller?.let(MediaController::releaseFuture)
        controller = null
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.hasExtra("demo") == true) vm.setDemo(intent.getBooleanExtra("demo", false))
        val uri = intent?.data ?: return
        if (uri.toString().startsWith(Config.REDIRECT_URI)) vm.onRedirect(uri)
    }
}
