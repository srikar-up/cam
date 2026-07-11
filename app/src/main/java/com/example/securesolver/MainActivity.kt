package com.example.securesolver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private var webRtcManager: WebRtcManager? = null
    private var signalingClient: FirebaseSignalingClient? = null
    private lateinit var lensIntegrationEngine: LensIntegrationEngine

    private var cameraService: CameraService? = null
    private var isBound = false

    // State parameters for UI credentials
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
    
    // UI Theme & Mode States
    private var activeThemeState = mutableStateOf("SLATE")
    private var isLoadingState = mutableStateOf(true)
    private var activeConnectionModeState = mutableStateOf("OPEN_AIR") // "OPEN_AIR" or "LAN"

    // Offline LAN TCP Connection Properties
    private val connectionManager = ConnectionManager()
    private var activeLanSocket: io.ktor.network.sockets.Socket? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
            
            webRtcManager?.let { manager ->
                try {
                    val localSurface = manager.initLocalVideoSource()
                    cameraService?.bindCameraToSurface(localSurface)
                    manager.addLocalVideoTrackToConnection()
                    // Set local video track state to render preview viewfinder on Host screen
                    localVideoTrackState.value = manager.getLocalVideoTrack()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Failed to bind camera to WebRTC", Toast.LENGTH_SHORT).show()
                }
            }
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

        // Load credentials and settings
        val prefs = getSharedPreferences("secure_solver_prefs", Context.MODE_PRIVATE)
        firebaseDbUrl.value = prefs.getString("db_url", BuildConfig.FIREBASE_DB_URL) ?: BuildConfig.FIREBASE_DB_URL
        firebaseApiKey.value = prefs.getString("api_key", BuildConfig.FIREBASE_API_KEY) ?: BuildConfig.FIREBASE_API_KEY
        firebaseAppId.value = prefs.getString("app_id", BuildConfig.FIREBASE_APP_ID) ?: BuildConfig.FIREBASE_APP_ID
        activeThemeState.value = prefs.getString("active_theme", "SLATE") ?: "SLATE"
        activeConnectionModeState.value = prefs.getString("connection_mode", "OPEN_AIR") ?: "OPEN_AIR"

        // Parse deep link room ID
        intent?.data?.let { uri ->
            val roomVal = uri.getQueryParameter("room")
            if (!roomVal.isNullOrEmpty()) {
                localRoomId.value = roomVal
            }
        }

        // 2-second splash loading delay
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
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
                    } else if (activeConnectionModeState.value == "OPEN_AIR" &&
                        (firebaseDbUrl.value.contains("fake-db") || firebaseApiKey.value.contains("FakeKey"))) {
                        Toast.makeText(context, "Please configure valid Firebase credentials in Settings first", Toast.LENGTH_LONG).show()
                        onOpenSettings()
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
                    if (activeConnectionModeState.value == "OPEN_AIR" &&
                        (firebaseDbUrl.value.contains("fake-db") || firebaseApiKey.value.contains("FakeKey"))) {
                        Toast.makeText(context, "Please configure valid Firebase credentials in Settings first", Toast.LENGTH_LONG).show()
                        onOpenSettings()
                    } else {
                        onRoleSelected("CLIENT")
                    }
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

                    Text(
                        text = "FIREBASE CONFIGURATION (OpenAir)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = firebaseDbUrl.value,
                        onValueChange = { firebaseDbUrl.value = it },
                        label = { Text("Database URL", color = Color(0xFF64748B)) },
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
                        label = { Text("API Key", color = Color(0xFF64748B)) },
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
                        label = { Text("Application ID", color = Color(0xFF64748B)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF334155),
                            focusedBorderColor = themeColors.third,
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    )

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
                }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
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

                    val intent = Intent(this@MainActivity, CameraService::class.java)
                    startService(intent)
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

                    webRtcManager!!.createPeerConnection(isCameraPhone = true)

                    lifecycleScope.launch(Dispatchers.IO) {
                        connectionManager.startServer(8890) { socket, line ->
                            activeLanSocket = socket
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

                    webRtcManager!!.createOffer { desc ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            serverLogs = "Server running on $localIp:8890. Awaiting Client connection..."
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            while (activeLanSocket == null) {
                                kotlinx.coroutines.delay(200)
                            }
                            connectionManager.sendMessage(activeLanSocket!!, "SDP_OFFER:${desc.description}")
                        }
                    }

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
                        firebaseDbUrl.value,
                        firebaseApiKey.value,
                        firebaseAppId.value
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
                // Viewfinder camera preview box on Server screen (viewing current hosting stream)
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
        var ipInput by remember { mutableStateOf(if (activeConnectionModeState.value == "LAN") "" else localRoomId.value) }
        val themeColors = getThemeColors()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(themeColors.first.first())
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
                            text = if (activeConnectionModeState.value == "LAN") "Enter Host IP Address" else "Connect to Video Stream",
                            color = Color(0xFF0F172A),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            label = { Text(if (activeConnectionModeState.value == "LAN") "e.g. 192.168.1.100:8890" else "6-Digit Room ID", color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF334155),
                                focusedBorderColor = themeColors.third,
                                unfocusedBorderColor = Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                if (activeConnectionModeState.value == "LAN") {
                                    if (ipInput.trim().isEmpty() || !ipInput.contains(":")) {
                                        Toast.makeText(this@MainActivity, "Please enter IP and Port (e.g. 192.168.1.100:8890)", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    try {
                                        connectAsClientLan(ipInput)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(this@MainActivity, "LAN connection error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    if (ipInput.trim().length != 6) {
                                        Toast.makeText(this@MainActivity, "Please enter a valid 6-digit Room ID", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    try {
                                        connectAsClient(ipInput)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.2f)
                                .background(Color.Black)
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
                        }

                        // Bottom 50% - Receiver client controllers
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.White)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.SpaceEvenly,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "STREAM RECEIVER CONTROLS",
                                color = Color(0xFF0F172A),
                                fontSize = 14.sp,
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
                                        .height(80.dp)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = themeColors.second)
                                ) {
                                    Text("Solve MCQ", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color.White)
                                }

                                Button(
                                    onClick = { triggerSolverCapture("CODE") },
                                    enabled = !isProcessing.value,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(80.dp)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9))
                                ) {
                                    Text("Solve Code", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color(0xFF0F172A))
                                }
                            }

                            Button(
                                onClick = { webRtcManager?.sendDataMessage("TOGGLE_FLASH") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .padding(8.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9))
                            ) {
                                Text("Toggle Camera Flash", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
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
            firebaseDbUrl.value,
            firebaseApiKey.value,
            firebaseAppId.value
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

                lifecycleScope.launch(Dispatchers.IO) {
                    connectionManager.startServer(port + 1) { _, line ->
                        handleLanSignalingMessage(line)
                    }
                }

                val receiveChannel = socket.openReadChannel()
                while (true) {
                    val line = io.ktor.utils.io.readUTF8Line(receiveChannel) ?: break
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
                cameraService?.captureHighQualityImage { bytes ->
                    sendImageOverDataChannel(bytes)
                }
            }
            message == "TOGGLE_FLASH" -> {
                cameraService?.toggleFlash()
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
                if (bitmap != null) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isProcessing.value = false
                        if (activeSolverPromptType.value == "MCQ") {
                            lensIntegrationEngine.solveMCQ(bitmap)
                        } else {
                            lensIntegrationEngine.solveCode(bitmap)
                        }
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
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        webRtcManager?.close()
        connectionManager.close()
        eglBase.release()
    }
}
