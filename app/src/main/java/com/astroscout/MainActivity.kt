package com.astroscout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.astroscout.ui.about.AboutScreen
import com.astroscout.ui.map.MapScreen
import com.astroscout.ui.settings.SettingsScreen
import com.astroscout.ui.theme.AstroScoutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AstroScoutApp).container
        setContent {
            AstroScoutTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "map") {
                    composable("map") {
                        MapScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenAbout = { nav.navigate("about") },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable("about") {
                        AboutScreen(onBack = { nav.popBackStack() }, isSkySimulated = container.isSkyQualitySimulated)
                    }
                }
            }
        }
    }
}
