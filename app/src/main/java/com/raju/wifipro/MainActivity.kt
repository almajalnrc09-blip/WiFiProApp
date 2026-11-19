package com.raju.wifipro

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

val BgDark = Color(0xFF0F172A)
val CardDark = Color(0xFF1E293B)
val AccentCyan = Color(0xFF38BDF8)
val AccentGreen = Color(0xFF22C55E)
val AccentAmber = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

class SpeedTestState {
    var isTesting by mutableStateOf(false)
    var phase by mutableStateOf("Ready to Test")
    var currentMbps by mutableDoubleStateOf(0.0)
    var pingMs by mutableLongStateOf(0L)
    var downloadMbps by mutableDoubleStateOf(0.0)
    var uploadMbps by mutableDoubleStateOf(0.0)
}

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        setContent {
            val darkScheme = darkColorScheme(
                primary = AccentCyan,
                background = BgDark,
                surface = CardDark,
                onBackground = TextPrimary,
                onSurface = TextPrimary
            )
            MaterialTheme(colorScheme = darkScheme) {
                WiFiProRoot()
            }
        }
    }
}

@Composable
fun WiFiProRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val appScope = rememberCoroutineScope()
    val speedState = remember { SpeedTestState() }

    val tabs = listOf(
        Pair("Speed", Icons.Default.Speed),
        Pair("Signal", Icons.Default.Wifi),
        Pair("Router", Icons.Default.Router),
        Pair("Devices", Icons.Default.Devices)
    )

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(item.second, contentDescription = item.first) },
                        label = { Text(item.first) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BgDark,
                            selectedTextColor = AccentCyan,
                            indicatorColor = AccentCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> SpeedTestTab(
                    state = speedState,
                    onStartTest = {
                        if (!speedState.isTesting) {
                            appScope.launch {
                                runFullSpeedTest(speedState)
                            }
                        }
                    }
                )
                1 -> SignalTab()
                3 -> DevicesTab()
            }

            // RouterTab kept alive in background
            Box(
                modifier = if (selectedTab == 2) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.size(0.dp).alpha(0f)
                }
            ) {
                RouterTab()
            }
        }
    }
}
