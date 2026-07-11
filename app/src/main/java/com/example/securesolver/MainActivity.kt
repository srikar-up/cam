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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    private var receivedImageBytes = ByteArrayOutputStream()
    private var isProcessing = mutableStateOf(false)
    private var activeSolverPromptType = mutableStateOf("")
    
    // UI Navigation State
    private var currentRoleState = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
            
            webRtcManager?.let { manager ->
                try {
                    val localSurface = manager.initLocalVideoSource()
                    cameraService?.bindCameraToSurface(localSurface)
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

        // Load credentials from SharedPreferences
        val prefs = getSharedPreferences("secure_solver_prefs", Context.MODE_PRIVATE)
        firebaseDbUrl.value = prefs.getString("db_url", BuildConfig.FIREBASE_DB_URL) ?: BuildConfig.FIREBASE_DB_URL
        firebaseApiKey.value = prefs.getString("api_key", BuildConfig.FIREBASE_API_KEY) ?: BuildConfig.FIREBASE_API_KEY
        firebaseAppId.value = prefs.getString("app_id", BuildConfig.FIREBASE_APP_ID) ?: BuildConfig.FIREBASE_APP_ID

        // Parse deep link room ID
        intent?.data?.let { uri ->
            val roomVal = uri.getQueryParameter("room")
            if (!roomVal.isNullOrEmpty()) {
                localRoomId.value = roomVal
            }
        }

        setContent {
            SecureSolverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF3F7FA) // Soft light canvas
                ) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        LaunchedEffect(localRoomId.value) {
            if (localRoomId.value.isNotEmpty() && currentRoleState.value == null) {
                currentRoleState.value = "CLIENT"
            }
        }

        if (currentRoleState.value == null) {
            RoleSelectionScreen { role ->
                currentRoleState.value = role
            }
        } else if (currentRoleState.value == "SERVER") {
            ServerScreen {
                resetConnectionState()
            }
        } else {
            ClientScreen {
                resetConnectionState()
            }
        }
    }

    private fun resetConnectionState() {
        // Safely unbind service if active
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        
        // Stop foreground camera service
        try {
            val intent = Intent(this, CameraService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Close peer connection
        try {
            webRtcManager?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        webRtcManager = null
        signalingClient = null
        cameraService = null
        
        // Reset states
        isConnected.value = false
        remoteVideoTrack.value = null
        localRoomId.value = ""
        currentRoleState.value = null
    }

    @Composable
    fun RoleSelectionScreen(onRoleSelected: (String) -> Unit) {
        val context = LocalContext.current
        var showSettingsDialog by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF0F4F8), Color(0xFFE2EAF4))
                    )
                )
        ) {
            // Floating Settings Icon (Clean Gear button)
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White, RoundedCornerShape(24.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configuration Setup",
                    tint = Color(0xFF1E293B)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(84.dp))

                // Premium Minimalist Title
                Text(
                    text = "SECURE SOLVER",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F172A),
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = "Modern Light P2P Streaming",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 84.dp)
                )

                // Action Selection Buttons (Modern Minimalist Solid Slate & Bordered layouts)
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(context, "Camera permission required to start Host", Toast.LENGTH_LONG).show()
                        } else if (firebaseDbUrl.value.contains("fake-db") || firebaseApiKey.value.contains("FakeKey")) {
                            Toast.makeText(context, "Please configure valid Firebase credentials first", Toast.LENGTH_LONG).show()
                            showSettingsDialog = true
                        } else {
                            onRoleSelected("SERVER")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("HOST CAMERA MODE", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (firebaseDbUrl.value.contains("fake-db") || firebaseApiKey.value.contains("FakeKey")) {
                            Toast.makeText(context, "Please configure valid Firebase credentials first", Toast.LENGTH_LONG).show()
                            showSettingsDialog = true
                        } else {
                            onRoleSelected("CLIENT")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.5.dp, Color(0xFF0F172A), RoundedCornerShape(16.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("RECEIVER CLIENT MODE", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A))
                }
            }
        }

        // Settings Dialog Modal
        if (showSettingsDialog) {
            Dialog(onDismissRequest = { showSettingsDialog = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(28.dp))
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = "Firebase Credentials",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        OutlinedTextField(
                            value = firebaseDbUrl.value,
                            onValueChange = { firebaseDbUrl.value = it },
                            label = { Text("Database URL", color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF334155),
                                focusedBorderColor = Color(0xFF4F46E5),
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
                                focusedBorderColor = Color(0xFF4F46E5),
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
                                focusedBorderColor = Color(0xFF4F46E5),
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
                                }.apply()
                                showSettingsDialog = false
                                Toast.makeText(context, "Credentials Saved Successfully", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("SAVE & CLOSE", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ServerScreen(onBack: () -> Unit) {
        var serverLogs by remember { mutableStateOf("Configuring signaling...") }
        val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "Pulsating alpha"
        )

        LaunchedEffect(Unit) {
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

                // Start background foreground service safely
                val intent = Intent(this@MainActivity, CameraService::class.java)
                startService(intent)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

                webRtcManager!!.createPeerConnection(isCameraPhone = true)
                webRtcManager!!.createOffer { desc ->
                    signalingClient?.createRoom(roomId, desc.description)
                    serverLogs = "Room $roomId created. Waiting for client handshake..."
                }

                // Listen to client handshake
                lifecycleScope.launch {
                    signalingClient?.observeAnswer(roomId)?.collectLatest { answerSdp ->
                        webRtcManager?.handleAnswer(answerSdp)
                    }
                }

                // Listen to ICE Candidates (Safe Casting)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF3F7FA))
                    )
                )
        ) {
            // Arrow Back Button to home page
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(Color(0xFF4F46E5).copy(alpha = alpha))
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Active Host",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp).align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = "CAMERA STREAMING ACTIVE",
                    color = Color(0xFF0F172A),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Room Connection ID",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = localRoomId.value,
                    color = Color(0xFF4F46E5),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.7f))
                        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        text = serverLogs,
                        color = if (isConnected.value) Color(0xFF10B981) else Color(0xFF334155),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    fun ClientScreen(onBack: () -> Unit) {
        var ipInput by remember { mutableStateOf(localRoomId.value) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF3F7FA))
        ) {
            // Header with Back Button (Display to allow exit)
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
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
                            text = "Connect to Video Stream",
                            color = Color(0xFF0F172A),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            label = { Text("6-Digit Room ID", color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF334155),
                                focusedBorderColor = Color(0xFF4F46E5),
                                unfocusedBorderColor = Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
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
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("CONNECT", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                } else {
                    // Split screen: Top 50% - Video surface, Bottom 50% - controllers
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
                                    color = Color(0xFF4F46E5),
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
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

        // Listen to ICE Candidates (Safe Casting)
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

    private fun triggerSolverCapture(promptType: String) {
        isProcessing.value = true
        activeSolverPromptType.value = promptType
        webRtcManager?.sendDataMessage("CAPTURE_HQ")
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
        eglBase.release()
    }
}
