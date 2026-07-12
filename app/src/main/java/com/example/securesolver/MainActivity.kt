package com.example.securesolver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.securesolver.ui.theme.SecureSolverTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.readUTF8Line

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private var webRtcManager: WebRtcManager? = null
    private var signalingClient: FirebaseSignalingClient? = null
    private lateinit var lensIntegrationEngine: LensIntegrationEngine

    private var cameraService: CameraService? = null
    private var isBound = false

    // State parameters for UI credentials (empty by default to hide personal credentials from settings UI)
    private var firebaseDbUrl = mutableStateOf("")
    private var firebaseApiKey = mutableStateOf("")
    private var firebaseAppId = mutableStateOf("")

    private var localRoomId = mutableStateOf("")
    private var isConnected = mutableStateOf(false)
    private var remoteVideoTrack = mutableStateOf<VideoTrack?>(null)
    private var localVideoTrackState = mutableStateOf<VideoTrack?>(null)
    private var receivedImageBytes = ByteArrayOutputStream()
    private var isProcessing = mutableStateOf(false)
    private var activeSolverPromptType = mutableStateOf("")
    
    // Captured photo preview state
    private var capturedPhotoState = mutableStateOf<Bitmap?>(null)
    
    // UI Theme & Mode States
    private var activeThemeState = mutableStateOf("SLATE")
    private var isLoadingState = mutableStateOf(true)
    private var activeConnectionModeState = mutableStateOf("OPEN_AIR") // "OPEN_AIR" or "LAN"

    // Offline LAN TCP Connection Properties
    private val connectionManager = ConnectionManager()
    private var activeLanSocket: io.ktor.network.sockets.Socket? = null

    // NSD Auto-Discovery properties
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    private val discoveredHostsList = mutableStateListOf<Pair<String, Int>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            Toast.makeText(this, "Camera permission is required to stream video", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eglBase = EglBase.create()
        lensIntegrationEngine = LensIntegrationEngine(this)

        // Request permissions on startup
        val requiredPermissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        // Load credentials and settings (slots remain empty by default to protect developer credentials)
        val prefs = getSharedPreferences("secure_solver_prefs", Context.MODE_PRIVATE)
        firebaseDbUrl.value = prefs.getString("db_url", "") ?: ""
        firebaseApiKey.value = prefs.getString("api_key", "") ?: ""
        firebaseAppId.value = prefs.getString("app_id", "") ?: ""
        activeThemeState.value = prefs.getString("active_theme", "SLATE") ?: "SLATE"
        activeConnectionModeState.value = prefs.getString("connection_mode", "OPEN_AIR") ?: "OPEN_AIR"

        // Parse deep link room ID
        intent?.data?.let { uri ->
            val roomVal = uri.getQueryParameter("room")
            if (!roomVal.isNullOrEmpty()) {
                localRoomId.value = roomVal
            }
        }

        // [UI/UX FIX] Removed artificial 2-second delay. Let the UI render immediately.
        lifecycleScope.launch {
            isLoadingState.value = false
        }

        setContent {
            SecureSolverTheme {
                val themeData = getThemeColors()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = themeData.first.first() // Light canvas base
                ) {
                    if (isLoadingState.value) {
                        LoadingScreen()
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun getThemeColors(): Triple<List<Color>, Color, Color> {
        return when (activeThemeState.value) {
            "EMERALD" -> Triple(
                listOf(Color(0xFFF0FDFA), Color(0xFFE6F4F1), Color(0xFFDFF0EB)),
                Color(0xFF0F766E), // Deep Emerald
                Color(0xFF14B8A6)  // Bright Teal
            )
            "ROSE" -> Triple(
                listOf(Color(0xFFFFF1F2), Color(0xFFFEE2E2), Color(0xFFFCE7F3)),
                Color(0xFFBE123C), // Deep Rose
                Color(0xFFF43F5E)  // Bright Pink
            )
            else -> Triple(
                listOf(Color(0xFFF8FAFC), Color(0xFFF1F5F9), Color(0xFFE2E8F0)),
                Color(0xFF0F172A), // Slate Grey
                Color(0xFF6366F1)  // Indigo
            )
        }
    }

    @Composable
    fun LoadingScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC))
                    )
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CAMDROID",
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF0F172A),
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "P2P REAL-TIME STREAM SOLVER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF64748B),
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 64.dp)
            )
            
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFE2E8F0),
                    strokeWidth = 3.dp,
                    modifier = Modifier.fillMaxSize()
                )
                CircularProgressIndicator(
                    color = Color(0xFF0F172A),
                    strokeWidth = 3.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    fun MainScreen() {
        var currentRole by remember { mutableStateOf<String?>(null) }
        var activeTheme by remember { mutableStateOf(activeThemeState.value) }

        LaunchedEffect(localRoomId.value) {
            if (localRoomId.value.isNotEmpty() && currentRole == null) {
                currentRole = "CLIENT"
            }
        }

        if (currentRole == "SETTINGS") {
            SettingsScreen(
                onBack = { currentRole = null },
                onThemeChanged = { newTheme ->
                    activeTheme = newTheme
                }
            )
        } else if (currentRole == "SERVER") {
            ServerScreen {
                resetConnectionState()
                currentRole = null
            }
        } else if (currentRole == "CLIENT") {
            ClientScreen {
                resetConnectionState()
                currentRole = null
            }
        } else {
            RoleSelectionScreen(
                onRoleSelected = { role ->
                    currentRole = role
                },
                onOpenSettings = {
                    currentRole = "SETTINGS"
                }
            )
        }
    }

    private fun resetConnectionState() {
        unregisterNsdService()
        stopNsdDiscovery()
        discoveredHostsList.clear()
        
        // [FIX] Explicitly reset UI processing state blocks to unfreeze greyed out buttons
        isProcessing.value = false

        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        
        try {
            val intent = Intent(this, CameraService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            webRtcManager?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            connectionManager.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        webRtcManager = null
        signalingClient = null
        cameraService = null
        activeLanSocket = null
        
        isConnected.value = false
        remoteVideoTrack.value = null
        localVideoTrackState.value = null
        localRoomId.value = ""
    }

    private fun getActiveFirebaseDbUrl(): String {
        return firebaseDbUrl.value.ifEmpty { BuildConfig.FIREBASE_DB_URL }
    }

    private fun getActiveFirebaseApiKey(): String {
        return firebaseApiKey.value.ifEmpty { BuildConfig.FIREBASE_API_KEY }
    }

    private fun getActiveFirebaseAppId(): String {
        return firebaseAppId.value.ifEmpty { BuildConfig.FIREBASE_APP_ID }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "0.0.0.0"
    }

    // --- NSD Auto-Discovery Helpers ---
    private fun registerNsdService(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = "CamdroidHost_${(100..999).random()}"
            serviceType = "_camdroid._tcp"
            setPort(port)
        }
        registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: android.net.nsd.NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsdManager?.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterNsdService() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registrationListener = null
    }

    private fun startNsdDiscovery() {
        discoveredHostsList.clear()
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager?.stopServiceDiscovery(this) } catch (e: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager?.stopServiceDiscovery(this) } catch (e: Exception) {}
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolvedServiceInfo: android.net.nsd.NsdServiceInfo) {
                        val host = resolvedServiceInfo.host.hostAddress
                        val port = resolvedServiceInfo.port
                        if (host != null) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (!discoveredHostsList.any { it.first == host }) {
                                    discoveredHostsList.add(Pair(host, port))
                                }
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                // If a host disconnects, remove it
                lifecycleScope.launch(Dispatchers.Main) {
                    discoveredHostsList.removeAll { it.first == serviceInfo.host?.hostAddress }
                }
            }
        }
        try {
            nsdManager?.discoverServices("_camdroid._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discoveryListener = null
    }

    // --- Save image to Public Android Gallery ---
    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
        val filename = "Camdroid_${System.currentTimeMillis()}.jpg"
        var fos: java.io.OutputStream? = null
        val contentResolver = context.contentResolver
        val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Camdroid")
            }
            contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val imagesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).toString()
            val file = File(imagesDir, filename)
            android.net.Uri.fromFile(file)
        }

        try {
            imageUri?.let { uri ->
                fos = contentResolver.openOutputStream(uri)
                fos?.let {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }
                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            fos?.close()
        }
    }

    @Composable
    fun RoleSelectionScreen(onRoleSelected: (String) -> Unit, onOpenSettings: () -> Unit) {
        val context = LocalContext.current
        val themeColors = getThemeColors()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = themeColors.first))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar containing Gear icon on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(2.dp, RoundedCornerShape(24.dp))
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open Settings",
                        tint = Color(0xFF334155),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Brand Header
            Text(
                text = "CAMDROID",
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF0F172A),
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = "High-Fidelity Camera Stream Solver",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF475569),
                modifier = Modifier.padding(bottom = 56.dp)
            )

            // Connection Mode Toggle Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf("OPEN_AIR" to "OpenAir (Cloud)", "LAN" to "Local LAN")
                    modes.forEach { mode ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (activeConnectionModeState.value == mode.first) themeColors.second else Color.Transparent
                                )
                                .clickable {
                                    activeConnectionModeState.value = mode.first
                                    val prefs = context.getSharedPreferences("secure_solver_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().putString("connection_mode", mode.first).apply()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.second,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeConnectionModeState.value == mode.first) Color.White else Color(0xFF475569),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }

            // Host Card
            Card(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Camera permission required to start Host", Toast.LENGTH_LONG).show()
                    } else {
                        onRoleSelected("SERVER")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .height(96.dp)
                    .shadow(6.dp, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = themeColors.second),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "HOST CAMERA MODE",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = if (activeConnectionModeState.value == "LAN") "Stream locally over offline LAN Wi-Fi network" else "Stream remote camera feeds via global P2P",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Client Card
            Card(
                onClick = {
                    onRoleSelected("CLIENT")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .border(1.5.dp, themeColors.second, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "RECEIVER CLIENT MODE",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = themeColors.second
                    )
                    Text(
                        text = if (activeConnectionModeState.value == "LAN") "Connect to nearby local server IP directly" else "Receive stream and execute solver commands",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun SettingsScreen(onBack: () -> Unit, onThemeChanged: (String) -> Unit) {
        val themeColors = getThemeColors()
        val context = LocalContext.current
        var advancedExpanded by remember { mutableStateOf(false) }
        var maskCredentials by remember { mutableStateOf(true) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = themeColors.first))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar containing back arrow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Go Back",
                        tint = Color(0xFF1E293B)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F172A)
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(28.dp))
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        text = "ACTIVE STYLE COLOR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val themes = listOf("SLATE" to "Slate", "EMERALD" to "Emerald", "ROSE" to "Rose")
                        themes.forEach { theme ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (activeThemeState.value == theme.first) Color(0xFFF8FAFC) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (activeThemeState.value == theme.first) themeColors.second else Color(0xFFE2E8F0),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { 
                                        activeThemeState.value = theme.first
                                        onThemeChanged(theme.first)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = theme.second,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeThemeState.value == theme.first) themeColors.second else Color(0xFF475569),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                    }

                    // Collapsible Advanced Configuration Section (Private Hosting)
                    Card(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Advanced Setup (Private Hosting)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF334155)
                                )
                                Text(
                                    text = if (advancedExpanded) "Collapse" else "Expand",
                                    fontSize = 11.sp,
                                    color = themeColors.second,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (advancedExpanded) {
                                Text(
                                    text = "Slots are empty by default (using developer pre-baked settings). Enter your custom credentials here to route P2P calls to your own private server configuration.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                                    lineHeight = 16.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Mask Credentials on Screen",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569)
                                    )
                                    Switch(
                                        checked = maskCredentials,
                                        onCheckedChange = { maskCredentials = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = themeColors.second)
                                    )
                                }

                                OutlinedTextField(
                                    value = firebaseDbUrl.value,
                                    onValueChange = { firebaseDbUrl.value = it },
                                    label = { Text("Private Database URL", color = Color(0xFF64748B)) },
                                    placeholder = { Text("Using default global URL") },
                                    visualTransformation = if (maskCredentials) PasswordVisualTransformation() else VisualTransformation.None,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFF0F172A),
                                        unfocusedTextColor = Color(0xFF334155),
                                        focusedBorderColor = themeColors.third,
                                        unfocusedBorderColor = Color(0xFFCBD5E1)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )

                                OutlinedTextField(
                                    value = firebaseApiKey.value,
                                    onValueChange = { firebaseApiKey.value = it },
                                    label = { Text("Private API Key", color = Color(0xFF64748B)) },
                                    placeholder = { Text("Using default global API key") },
                                    visualTransformation = if (maskCredentials) PasswordVisualTransformation() else VisualTransformation.None,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFF0F172A),
                                        unfocusedTextColor = Color(0xFF334155),
                                        focusedBorderColor = themeColors.third,
                                        unfocusedBorderColor = Color(0xFFCBD5E1)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )

                                OutlinedTextField(
                                    value = firebaseAppId.value,
                                    onValueChange = { firebaseAppId.value = it },
                                    label = { Text("Private Application ID", color = Color(0xFF64748B)) },
                                    placeholder = { Text("Using default global App ID") },
                                    visualTransformation = if (maskCredentials) PasswordVisualTransformation() else VisualTransformation.None,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFF0F172A),
                                        unfocusedTextColor = Color(0xFF334155),
                                        focusedBorderColor = themeColors.third,
                                        unfocusedBorderColor = Color(0xFFCBD5E1)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val prefs = context.getSharedPreferences("secure_solver_prefs", Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("db_url", firebaseDbUrl.value)
                                putString("api_key", firebaseApiKey.value)
                                putString("app_id", firebaseAppId.value)
                                putString("active_theme", activeThemeState.value)
                            }.apply()
                            onBack()
                            Toast.makeText(context, "Settings Saved Successfully", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = themeColors.second),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("SAVE CONFIGURATION", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Version: v1.4.0",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    fun ServerScreen(onBack: () -> Unit) {
        var serverLogs by remember { mutableStateOf("Configuring signaling...") }
        val themeColors = getThemeColors()
        val localIp = getLocalIpAddress()

        LaunchedEffect(Unit) {
            if (activeConnectionModeState.value == "LAN") {
                serverLogs = "Local LAN Mode selected. Starting Ktor TCP Server..."
                
                try {
                    webRtcManager = WebRtcManager(
                        context = this@MainActivity,
                        eglBaseContext = eglBase.eglBaseContext,
                        onIceCandidateGenerated = { candidate ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                activeLanSocket?.let { socket ->
                                    connectionManager.sendMessage(socket, "ICE_CANDIDATE:${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
                                }
                            }
                        },
                        onRemoteStreamReceived = {},
                        onDataChannelMessage = { message ->
                            handleControlMessage(message)
                        },
                        onConnectionStateChanged = { connected ->
                            isConnected.value = connected
                            if (connected) {
                                serverLogs = "Offline Local P2P Connection Established!"
                            }
                        }
                    )

                    // CRITICAL: Initialize local video tracks BEFORE generating the SDP Offer
                    webRtcManager!!.initLocalVideoSource()
                    localVideoTrackState.value = webRtcManager!!.getLocalVideoTrack()

                    // Start camera foreground service safely
                    val intent = Intent(this@MainActivity, CameraService::class.java)
                    startService(intent)
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

                    webRtcManager!!.createPeerConnection(isCameraPhone = true)

                    lifecycleScope.launch(Dispatchers.IO) {
                        connectionManager.startServer(8890) { socket, line ->
                            // [FIX] Do NOT generate offer until the socket actually connects 
                            // to ensure ICE candidates aren't lost in the void.
                            if (activeLanSocket == null) {
                                activeLanSocket = socket
                                lifecycleScope.launch(Dispatchers.Main) {
                                    webRtcManager!!.createOffer { desc ->
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            connectionManager.sendMessage(socket, "SDP_OFFER:${desc.description}")
                                        }
                                    }
                                }
                            }
                            
                            if (line.startsWith("SDP_ANSWER:")) {
                                val sdp = line.substringAfter("SDP_ANSWER:")
                                lifecycleScope.launch(Dispatchers.Main) {
                                    webRtcManager?.handleAnswer(sdp)
                                }
                            } else if (line.startsWith("ICE_CANDIDATE:")) {
                                val content = line.substringAfter("ICE_CANDIDATE:")
                                val parts = content.split("|")
                                if (parts.size == 3) {
                                    val sdpMid = parts[0]
                                    val sdpMLineIndex = parts[1].toInt()
                                    val sdp = parts[2]
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                                    }
                                }
                            }
                        }
                    }

                    // Register local service to Wi-Fi for auto-discovery
                    registerNsdService(8890)

                } catch (e: Exception) {
                    e.printStackTrace()
                    serverLogs = "Local Server failed: ${e.message}"
                }

            } else {
                // Firebase OpenAir Signaling
                val roomId = (100000..999999).random().toString()
                localRoomId.value = roomId

                try {
                    signalingClient = FirebaseSignalingClient(
                        this@MainActivity,
                        getActiveFirebaseDbUrl(),
                        getActiveFirebaseApiKey(),
                        getActiveFirebaseAppId()
                    )

                    webRtcManager = WebRtcManager(
                        context = this@MainActivity,
                        eglBaseContext = eglBase.eglBaseContext,
                        onIceCandidateGenerated = { candidate ->
                            signalingClient?.sendIceCandidate(roomId, true, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                        },
                        onRemoteStreamReceived = {},
                        onDataChannelMessage = { message ->
                            handleControlMessage(message)
                        },
                        onConnectionStateChanged = { connected ->
                            isConnected.value = connected
                            if (connected) {
                                serverLogs = "P2P WebRTC Connection Established."
                            } else {
                                serverLogs = "P2P Disconnected."
                            }
                        }
                    )

                    // CRITICAL: Initialize local video tracks BEFORE generating the SDP Offer
                    webRtcManager!!.initLocalVideoSource()
                    localVideoTrackState.value = webRtcManager!!.getLocalVideoTrack()

                    val intent = Intent(this@MainActivity, CameraService::class.java)
                    startService(intent)
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

                    webRtcManager!!.createPeerConnection(isCameraPhone = true)
                    webRtcManager!!.createOffer { desc ->
                        signalingClient?.createRoom(roomId, desc.description)
                        serverLogs = "Room $roomId created. Waiting for client handshake..."
                    }

                    lifecycleScope.launch {
                        signalingClient?.observeAnswer(roomId)?.collectLatest { answerSdp ->
                            webRtcManager?.handleAnswer(answerSdp)
                        }
                    }

                    lifecycleScope.launch {
                        signalingClient?.observeIceCandidates(roomId)?.collectLatest { map ->
                            val isCamera = map["isCamera"] as? Boolean ?: false
                            if (!isCamera) {
                                val sdp = map["sdp"] as? String ?: ""
                                val sdpMid = map["sdpMid"] as? String ?: ""
                                val sdpMLineIndex = (map["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                                if (sdp.isNotEmpty()) {
                                    webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    serverLogs = "Signaling initialization error: ${e.message}"
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = themeColors.first))
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(48.dp)
                    .shadow(2.dp, RoundedCornerShape(24.dp))
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Go Back",
                    tint = Color(0xFF1E293B)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 84.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Viewfinder camera preview box on Server screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .shadow(6.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black)
                        .border(1.dp, themeColors.second.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                ) {
                    val track = localVideoTrackState.value
                    if (track != null) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    init(eglBase.eglBaseContext, null)
                                    setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                    track.addSink(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(
                            color = themeColors.third,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CAMERA STREAMING ACTIVE",
                        color = Color(0xFF0F172A),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (activeConnectionModeState.value == "LAN") {
                        Text(
                            text = "Local LAN Server IP",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$localIp:8890",
                            color = themeColors.second,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    } else {
                        Text(
                            text = "Room Connection ID",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = localRoomId.value,
                            color = themeColors.second,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = serverLogs,
                        color = if (isConnected.value) Color(0xFF10B981) else Color(0xFF334155),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    @Composable
    fun ClientScreen(onBack: () -> Unit) {
        var ipInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("8890") }
        var roomIdInput by remember { mutableStateOf(localRoomId.value) }
        val themeColors = getThemeColors()
        val context = LocalContext.current
        
        // [UI/UX FIX] Add state to track fullscreen toggle
        var isFullScreen by remember { mutableStateOf(false) }

        // Run Local Wi-Fi discovery when entering ClientScreen in LAN connection mode
        DisposableEffect(Unit) {
            if (activeConnectionModeState.value == "LAN") {
                startNsdDiscovery()
            }
            onDispose {
                stopNsdDiscovery()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // [UI/UX FIX] Darken entire background when in fullscreen mode
                .background(if (isFullScreen) Color.Black else themeColors.first.first())
        ) {
            // [UI/UX FIX] Hide the top back button to create an immersive edge-to-edge view
            if (!isFullScreen) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                        .size(48.dp)
                        .shadow(2.dp, RoundedCornerShape(24.dp))
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Go Back",
                        tint = Color(0xFF1E293B)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                if (!isConnected.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (activeConnectionModeState.value == "LAN") "Enter Host Connection Details" else "Connect to Video Stream",
                            color = Color(0xFF0F172A),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        if (activeConnectionModeState.value == "LAN") {
                            // Render list of discovered LAN hosts
                            Text(
                                text = "DISCOVERED LAN HOSTS (AUTO-CONNECT)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B),
                                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                            )
                            
                            if (discoveredHostsList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = themeColors.second)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("Scanning local Wi-Fi for active hosts...", fontSize = 12.sp, color = Color(0xFF64748B))
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                    discoveredHostsList.forEach { hostPair ->
                                        Card(
                                            onClick = {
                                                try {
                                                    connectAsClientLan("${hostPair.first}:${hostPair.second}")
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Error connecting: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .shadow(2.dp, RoundedCornerShape(12.dp)),
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("Active Camdroid Host", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = themeColors.second)
                                                    Text("IP: ${hostPair.first}:${hostPair.second}", fontSize = 11.sp, color = Color(0xFF64748B))
                                                }
                                                Text("TAP TO CONNECT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = themeColors.third)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "OR FILL DETAILS MANUALLY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = ipInput,
                                onValueChange = { ipInput = it },
                                label = { Text("Host IP Address (e.g. 192.168.1.100)", color = Color(0xFF64748B)) },
                                // [UI/UX FIX] Enable numeric keyboard for IP
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF334155),
                                    focusedBorderColor = themeColors.third,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )

                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it },
                                label = { Text("Port (default 8890)", color = Color(0xFF64748B)) },
                                // [UI/UX FIX] Enable numeric keyboard for Port
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF334155),
                                    focusedBorderColor = themeColors.third,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = roomIdInput,
                                onValueChange = { roomIdInput = it },
                                label = { Text("6-Digit Room ID", color = Color(0xFF64748B)) },
                                // [UI/UX FIX] Enable numeric keyboard for Room ID
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF334155),
                                    focusedBorderColor = themeColors.third,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                if (activeConnectionModeState.value == "LAN") {
                                    if (ipInput.trim().isEmpty() || portInput.trim().isEmpty()) {
                                        Toast.makeText(this@MainActivity, "Please enter both IP Address and Port", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    try {
                                        connectAsClientLan("$ipInput:$portInput")
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(this@MainActivity, "LAN connection error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    if (roomIdInput.trim().length != 6) {
                                        Toast.makeText(this@MainActivity, "Please enter a valid 6-digit Room ID", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    try {
                                        connectAsClient(roomIdInput)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(this@MainActivity, "Connection setup failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = themeColors.second),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("CONNECT", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            // [UI/UX FIX] Dynamically switch between the split-screen weight and full-screen size
                            modifier = if (isFullScreen) {
                                Modifier.fillMaxSize().background(Color.Black)
                            } else {
                                Modifier.fillMaxWidth().weight(1.2f).background(Color.Black)
                            }
                        ) {
                            if (remoteVideoTrack.value != null) {
                                AndroidView(
                                    factory = { ctx ->
                                        SurfaceViewRenderer(ctx).apply {
                                            init(eglBase.eglBaseContext, null)
                                            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                            remoteVideoTrack.value?.addSink(this)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(
                                    color = themeColors.third,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            
                            // [UI/UX FIX] Floating translucent toggle button overlaid on the video
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                                    .size(52.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .clickable { isFullScreen = !isFullScreen },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isFullScreen) "EXIT" else "FULL",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        // [UI/UX FIX] Completely remove the bottom controls from the view hierarchy when in fullscreen
                        if (!isFullScreen) {
                            // Bottom 50% - Receiver client controllers
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1.1f)
                                    .background(Color.White)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.SpaceEvenly,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "STREAM RECEIVER CONTROLS",
                                    color = Color(0xFF0F172A),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = { triggerSolverCapture("MCQ") },
                                        enabled = !isProcessing.value,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(72.dp)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = themeColors.second)
                                    ) {
                                        // [UI/UX FIX] Added Icons and Column layout for buttons
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Search, contentDescription = "MCQ", modifier = Modifier.size(24.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Solve MCQ", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color.White)
                                        }
                                    }

                                    Button(
                                        onClick = { triggerSolverCapture("CODE") },
                                        enabled = !isProcessing.value,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(72.dp)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9))
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Build, contentDescription = "Code", modifier = Modifier.size(24.dp), tint = Color(0xFF0F172A))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Solve Code", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color(0xFF0F172A))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = { triggerSolverCapture("PREVIEW") },
                                        enabled = !isProcessing.value,
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .height(64.dp)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = themeColors.second)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Create, contentDescription = "Capture", modifier = Modifier.size(18.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("High-Res Photo", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }

                                    Button(
                                        onClick = { webRtcManager?.sendDataMessage("TOGGLE_FLASH") },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(64.dp)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9))
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = "Flash", modifier = Modifier.size(18.dp), tint = Color(0xFF334155))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Flash", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Captured High-Res Photo Preview Dialog
        if (capturedPhotoState.value != null) {
            Dialog(onDismissRequest = { capturedPhotoState.value = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Captured High-Res Image",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        capturedPhotoState.value?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Host Capture",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        // Button row to save to gallery or dismiss
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(
                                onClick = { 
                                    capturedPhotoState.value?.let { bitmap ->
                                        saveBitmapToGallery(this@MainActivity, bitmap)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp).padding(end = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = themeColors.second),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("SAVE TO GALLERY", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                            }
                            
                            Button(
                                onClick = { capturedPhotoState.value = null },
                                modifier = Modifier.weight(1f).height(48.dp).padding(start = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("CLOSE", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF334155))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connectAsClient(roomId: String) {
        signalingClient = FirebaseSignalingClient(
            this,
            getActiveFirebaseDbUrl(),
            getActiveFirebaseApiKey(),
            getActiveFirebaseAppId()
        )

        webRtcManager = WebRtcManager(
            context = this,
            eglBaseContext = eglBase.eglBaseContext,
            onIceCandidateGenerated = { candidate ->
                signalingClient?.sendIceCandidate(roomId, false, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            },
            onRemoteStreamReceived = { track ->
                remoteVideoTrack.value = track
            },
            onDataChannelMessage = { message ->
                handleControlMessage(message)
            },
            onConnectionStateChanged = { connected ->
                isConnected.value = connected
            }
        )

        webRtcManager?.createPeerConnection(isCameraPhone = false)

        lifecycleScope.launch {
            signalingClient?.observeOffer(roomId)?.collectLatest { offerSdp ->
                webRtcManager?.handleOffer(offerSdp) { answerDesc ->
                    signalingClient?.sendAnswer(roomId, answerDesc.description)
                }
            }
        }

        lifecycleScope.launch {
            signalingClient?.observeIceCandidates(roomId)?.collectLatest { map ->
                val isCamera = map["isCamera"] as? Boolean ?: false
                if (isCamera) {
                    val sdp = map["sdp"] as? String ?: ""
                    val sdpMid = map["sdpMid"] as? String ?: ""
                    val sdpMLineIndex = (map["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                    if (sdp.isNotEmpty()) {
                        webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                    }
                }
            }
        }
    }

    private fun connectAsClientLan(targetIpPort: String) {
        val parts = targetIpPort.split(":")
        val ip = parts[0]
        val port = parts[1].toInt()

        webRtcManager = WebRtcManager(
            context = this,
            eglBaseContext = eglBase.eglBaseContext,
            onIceCandidateGenerated = { candidate ->
                lifecycleScope.launch(Dispatchers.IO) {
                    activeLanSocket?.let { socket ->
                        connectionManager.sendMessage(socket, "ICE_CANDIDATE:${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
                    }
                }
            },
            onRemoteStreamReceived = { track ->
                remoteVideoTrack.value = track
            },
            onDataChannelMessage = { message ->
                handleControlMessage(message)
            },
            onConnectionStateChanged = { connected ->
                isConnected.value = connected
            }
        )

        webRtcManager?.createPeerConnection(isCameraPhone = false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = connectionManager.connectToServer(ip, port)
                activeLanSocket = socket

                // Immediately send CLIENT_CONNECT to kickstart the server's offer generation
                connectionManager.sendMessage(socket, "CLIENT_CONNECT")

                val receiveChannel = socket.openReadChannel()
                while (true) {
                    val line = receiveChannel.readUTF8Line() ?: break
                    handleLanSignalingMessage(line)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "LAN connection error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleLanSignalingMessage(line: String) {
        if (line.startsWith("SDP_OFFER:")) {
            val sdp = line.substringAfter("SDP_OFFER:")
            lifecycleScope.launch(Dispatchers.Main) {
                webRtcManager?.handleOffer(sdp) { answerDesc ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        activeLanSocket?.let { socket ->
                            connectionManager.sendMessage(socket, "SDP_ANSWER:${answerDesc.description}")
                        }
                    }
                }
            }
        } else if (line.startsWith("ICE_CANDIDATE:")) {
            val content = line.substringAfter("ICE_CANDIDATE:")
            val parts = content.split("|")
            if (parts.size == 3) {
                val sdpMid = parts[0]
                val sdpMLineIndex = parts[1].toInt()
                val sdp = parts[2]
                lifecycleScope.launch(Dispatchers.Main) {
                    webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                }
            }
        }
    }

    private fun triggerSolverCapture(promptType: String) {
        isProcessing.value = true
        activeSolverPromptType.value = promptType
        
        if (activeConnectionModeState.value == "LAN") {
            lifecycleScope.launch(Dispatchers.IO) {
                activeLanSocket?.let { socket ->
                    connectionManager.sendMessage(socket, "CAPTURE_HQ")
                }
            }
        } else {
            webRtcManager?.sendDataMessage("CAPTURE_HQ")
        }
    }

    private fun handleControlMessage(message: String) {
        when {
            message == "CAPTURE_HQ" -> {
                webRtcManager?.captureCurrentFrame { bitmap ->
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    sendImageOverDataChannel(stream.toByteArray())
                }
            }
            message == "TOGGLE_FLASH" -> {
                // No-op (handled natively by WebRTC camera capturer parameters)
            }
            message.startsWith("START_IMG:") -> {
                receivedImageBytes.reset()
            }
            message.startsWith("CHUNK:") -> {
                val base64Data = message.substringAfter("CHUNK:")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                receivedImageBytes.write(bytes, 0, bytes.size)
            }
            message == "END_IMG" -> {
                val bytes = receivedImageBytes.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                
                // [FIX] Always clear processing state even if image parsing fails completely
                lifecycleScope.launch(Dispatchers.Main) {
                    isProcessing.value = false
                    if (bitmap != null) {
                        if (activeSolverPromptType.value == "PREVIEW") {
                            capturedPhotoState.value = bitmap
                        } else if (activeSolverPromptType.value == "MCQ") {
                            lensIntegrationEngine.solveMCQ(bitmap)
                        } else {
                            lensIntegrationEngine.solveCode(bitmap)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Image Transfer Error: Corrupted Data", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun sendImageOverDataChannel(bytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (activeConnectionModeState.value == "LAN") {
                activeLanSocket?.let { socket ->
                    connectionManager.sendMessage(socket, "START_IMG:${bytes.size}")
                    var offset = 0
                    val chunkSize = 16384
                    while (offset < bytes.size) {
                        val end = minOf(offset + chunkSize, bytes.size)
                        val chunk = bytes.copyOfRange(offset, end)
                        val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        connectionManager.sendMessage(socket, "CHUNK:$base64Chunk")
                        offset = end
                        kotlinx.coroutines.delay(12)
                    }
                    connectionManager.sendMessage(socket, "END_IMG")
                }
            } else {
                webRtcManager?.sendDataMessage("START_IMG:${bytes.size}")
                var offset = 0
                val chunkSize = 16384
                while (offset < bytes.size) {
                    val end = minOf(offset + chunkSize, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)
                    val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    val success = webRtcManager?.sendDataMessage("CHUNK:$base64Chunk") ?: false
                    if (success) {
                        offset = end
                    }
                    kotlinx.coroutines.delay(12)
                }
                webRtcManager?.sendDataMessage("END_IMG")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            val roomVal = uri.getQueryParameter("room")
            if (!roomVal.isNullOrEmpty()) {
                localRoomId.value = roomVal
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resetConnectionState()
        eglBase.release()
    }
}
