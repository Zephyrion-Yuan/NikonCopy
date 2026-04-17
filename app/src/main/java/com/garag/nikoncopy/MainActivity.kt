package com.garag.nikoncopy

import android.os.Bundle
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
import com.garag.nikoncopy.viewmodel.CopyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CopyService.ensureChannel(this)
        setContent {
            NikonCopyTheme {
                AppNav()
            }
        }
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
