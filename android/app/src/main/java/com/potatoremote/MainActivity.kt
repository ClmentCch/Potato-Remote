package com.potatoremote

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.potatoremote.control.AccessibilityBridge
import com.potatoremote.service.ProjectionConsent
import com.potatoremote.service.ShareService
import com.potatoremote.settings.Prefs
import com.potatoremote.ui.SharePhase
import com.potatoremote.ui.ShareState
import com.potatoremote.ui.components.PotatoScreenSurface
import com.potatoremote.ui.screens.ActiveScreen
import com.potatoremote.ui.screens.CreateScreen
import com.potatoremote.ui.screens.HomeScreen
import com.potatoremote.ui.screens.SettingsScreen
import com.potatoremote.ui.theme.PotatoTheme
import com.potatoremote.ui.theme.Cream

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var pendingConfig: ShareService.StartConfig? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingConfig
        pendingConfig = null
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null && config != null) {
            ProjectionConsent.save(result.resultCode, data)
            ShareService.start(this, config)
        } else {
            ShareState.phase.value = SharePhase.IDLE
            ShareState.error.value = "Partage d'écran annulé."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = Cream.toArgb()

        setContent {
            PotatoTheme {
                PotatoRoot(
                    prefs = prefs,
                    onStartSharing = { config ->
                        pendingConfig = config
                        ShareState.phase.value = SharePhase.STARTING
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShareState.accessibilityReady.value = AccessibilityBridge.isReady
    }
}

@Composable
fun PotatoRoot(
    prefs: Prefs,
    onStartSharing: (ShareService.StartConfig) -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf("home") }

    val phase by ShareState.phase.collectAsStateWithLifecycle()
    val session by ShareState.session.collectAsStateWithLifecycle()
    val settings by ShareState.settings.collectAsStateWithLifecycle()
    val viewers by ShareState.viewers.collectAsStateWithLifecycle()
    val activity by ShareState.activity.collectAsStateWithLifecycle()
    val error by ShareState.error.collectAsStateWithLifecycle()
    val controlActive by ShareState.controlActive.collectAsStateWithLifecycle()
    val keyboardActive by ShareState.keyboardActive.collectAsStateWithLifecycle()
    val accessibilityReady by ShareState.accessibilityReady.collectAsStateWithLifecycle()
    val lastStats by ShareState.lastViewerStats.collectAsStateWithLifecycle()

    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ShareState.accessibilityReady.value = com.potatoremote.control.AccessibilityBridge.isReady
    }

    LaunchedEffect(phase) {
        when (phase) {
            SharePhase.LIVE -> screen = "active"
            SharePhase.IDLE -> if (screen == "active") screen = "home"
            else -> Unit
        }
    }

    LaunchedEffect(error, phase) {
        val message = error
        if (message != null && phase != SharePhase.ERROR) {
            snackbar.showSnackbar(message)
            ShareState.error.value = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        PotatoScreenSurface {
            Crossfade(targetState = screen, animationSpec = tween(220), label = "potato-screen") { current ->
                when (current) {
                    "create" -> CreateScreen(
                        prefs = prefs,
                        onBack = { screen = "home" },
                        onStart = onStartSharing,
                    )
                    "active" -> {
                        val activeSession = session
                        if (activeSession != null) {
                            ActiveScreen(
                                session = activeSession,
                                settings = settings,
                                viewers = viewers,
                                activity = activity,
                                controlActive = controlActive,
                                keyboardActive = keyboardActive,
                                accessibilityReady = accessibilityReady,
                                lastStats = lastStats,
                                onSettings = { screen = "settings" },
                                onStop = { ShareService.stop(context) },
                            )
                        } else {
                            LaunchedEffect(Unit) { screen = "home" }
                        }
                    }
                    "settings" -> SettingsScreen(prefs = prefs, onBack = { screen = "home" })
                    else -> HomeScreen(
                        prefs = prefs,
                        onStart = { screen = "create" },
                        onSettings = { screen = "settings" },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }

    if (phase == SharePhase.ERROR && error != null) {
        AlertDialog(
            onDismissRequest = {
                ShareState.error.value = null
                ShareState.phase.value = SharePhase.IDLE
            },
            title = { Text("Partage impossible") },
            text = { Text(error!!) },
            confirmButton = {
                TextButton(onClick = {
                    ShareState.error.value = null
                    ShareState.phase.value = SharePhase.IDLE
                }) { Text("Compris") }
            },
        )
    }
}
