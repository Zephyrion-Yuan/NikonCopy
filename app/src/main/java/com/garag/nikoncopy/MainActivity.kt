package com.garag.nikoncopy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.garag.nikoncopy.copy.CopyService
import com.garag.nikoncopy.ui.HomeScreen
import com.garag.nikoncopy.ui.SettingsScreen
import com.garag.nikoncopy.ui.theme.NikonCopyTheme
import com.garag.nikoncopy.util.LogCollector
import com.garag.nikoncopy.viewmodel.CopyViewModel

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lifecycle logs are critical for diagnosing the HyperOS SAF picker
        // dismissal bug — if Android destroys+recreates us while the picker is
        // foreground, the launcher result registration is lost and the picker
        // ends up dispatching to nobody. The pattern in logs would be
        // onPause → onStop → (picker stuff) → onCreate (savedState != null) → onResume.
        Log.i(TAG, "onCreate savedState=${savedInstanceState != null} intent=${intent?.action} flags=0x${intent?.flags?.toString(16)}")
        enableEdgeToEdge()
        CopyService.ensureChannel(this)
        setContent {
            NikonCopyTheme {
                AppNav()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        // Background→foreground after a long idle? Drop the now-stale log
        // lines so the next capture starts clean. Cheap no-op otherwise.
        LogCollector.resetBufferIfStale()
    }

    override fun onPause() {
        Log.i(TAG, "onPause isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
        super.onPause()
    }

    override fun onStop() {
        Log.i(TAG, "onStop isFinishing=$isFinishing")
        super.onStop()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy isFinishing=$isFinishing")
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Log.i(TAG, "onSaveInstanceState")
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun AppNav() {
    val navController = rememberNavController()
    val vm: CopyViewModel = viewModel()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
