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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private var webRtcManager: WebRtcManager? = null
    private var signalingClient: FirebaseSignalingClient? = null
    private lateinit var lensIntegrationEngine: LensIntegrationEngine

    private var cameraService: CameraService? = null
    private var isBound = false

    // Firebase Credentials (configurable via UI)
    private var firebaseDbUrl = mutableStateOf("https://securesolver-default-rtdb.firebaseio.com")
    private var firebaseApiKey = mutableStateOf("AIzaSyFakeApiKey1234567890")
    private var firebaseAppId = mutableStateOf("1:123456789:android:fakeappid")

    // Peer connection state tracking
    private var localRoomId = mutableStateOf("")
    private var isConnected = mutableStateOf(false)
    private var remoteVideoTrack = mutableStateOf<VideoTrack?>(null)
    private var receivedImageBytes = ByteArrayOutputStream()
    private var expectedImageSize = 0
    private var isProcessing = mutableStateOf(false)
    private var activeSolverPromptType = mutableStateOf("")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
            
            // If server mode is already active, bind camera to WebRTC local source surface
            webRtcManager?.let { manager ->
                val localSurface = manager.initLocalVideoSource()
                cameraService?.bindCameraToSurface(localSurface)
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
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eglBase = EglBase.create()
        lensIntegrationEngine = LensIntegrationEngine(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }

        // Handle incoming deep link
        intent?.data?.let { uri ->
            val room = uri.getQueryParameter("room")
            if (!room.isNullOrEmpty()) {
                localRoomId.value = room
            }
        }

        setContent {
            SecureSolverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        var currentRole by remember { mutableStateOf<String?>(null) }

        // Auto-route to CLIENT role if deep link populated room ID
        LaunchedEffect(localRoomId.value) {
            if (localRoomId.value.isNotEmpty() && currentRole == null) {
                currentRole = "CLIENT"
            }
        }

        if (currentRole == null) {
            RoleSelectionScreen { role ->
                currentRole = role
            }
        } else if (currentRole == "SERVER") {
            ServerScreen()
        } else {
            ClientScreen()
        }
    }

    @Composable
    fun RoleSelectionScreen(onRoleSelected: (String) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                    )
                )
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SECURE SOLVER P2P",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Firebase Config Fields
            OutlinedTextField(
                value = firebaseDbUrl.value,
                onValueChange = { firebaseDbUrl.value = it },
                label = { Text("Firebase DB URL", color = Color.LightGray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = firebaseApiKey.value,
                onValueChange = { firebaseApiKey.value = it },
                label = { Text("API Key", color = Color.LightGray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = firebaseAppId.value,
                onValueChange = { firebaseAppId.value = it },
                label = { Text("Application ID", color = Color.LightGray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )

            Button(
                onClick = { onRoleSelected("SERVER") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2DE2))
            ) {
                Text("HOST CAMERA", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onRoleSelected("CLIENT") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0))
            ) {
                Text("JOIN STREAMER", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun ServerScreen() {
        var serverLogs by remember { mutableStateOf("Configuring signaling...") }
        val clipboardManager = LocalClipboardManager.current

        LaunchedEffect(Unit) {
            val roomId = (100000..999999).random().toString()
            localRoomId.value = roomId

            signalingClient = FirebaseSignalingClient(
                this@MainActivity,
                firebaseDbUrl.value,
                firebaseApiKey.value,
                firebaseAppId.value
            )

            webRtcManager = WebRtcManager(
                this@MainActivity,
                eglBase.eglBaseContext,
                onIceCandidateGenerated = { candidate ->
                    signalingClient?.sendIceCandidate(roomId, true, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                },
                onRemoteStreamReceived = {},
                onDataChannelMessage = { message ->
                    handleControlMessage(message)
                }
            )

            // Start Foreground Service
            val intent = Intent(this@MainActivity, CameraService::class.java)
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            val localSurface = webRtcManager!!.initLocalVideoSource()
            cameraService?.bindCameraToSurface(localSurface)

            webRtcManager!!.createPeerConnection(isCameraPhone = true)
            webRtcManager!!.createOffer { desc ->
                signalingClient?.createRoom(roomId, desc.description)
                serverLogs = "Room $roomId created. Deep link copied to clipboard!"
                clipboardManager.setText(AnnotatedString("https://myapp.com?room=$roomId"))
            }

            // Observe Answer SDP
            lifecycleScope.launch {
                signalingClient?.observeAnswer(roomId)?.collectLatest { answerSdp ->
                    webRtcManager?.handleAnswer(answerSdp)
                    isConnected.value = true
                    serverLogs = "Client Connected!"
                }
            }

            // Observe Remote Client ICE candidates
            lifecycleScope.launch {
                signalingClient?.observeIceCandidates(roomId)?.collectLatest { map ->
                    val isCamera = map["isCamera"] as? Boolean ?: false
                    if (!isCamera) {
                        val sdp = map["sdp"] as String
                        val sdpMid = map["sdpMid"] as String
                        val sdpMLineIndex = (map["sdpMLineIndex"] as Long).toInt()
                        webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CAMERA STREAMER ACTIVE",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Room ID: ${localRoomId.value}",
                color = Color.Green,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = serverLogs,
                color = Color.LightGray,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    fun ClientScreen() {
        var ipInput by remember { mutableStateOf(localRoomId.value) }

        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF121212))
        ) {
            if (!isConnected.value) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Join Video Stream Room",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("6-Digit Room ID", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            connectAsClient(ipInput)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0)),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text("CONNECT")
                    }
                }
            } else {
                // Top 50% - WebRTC Stream Display
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                    contentAlignment = Alignment.Center
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
                        CircularProgressIndicator(color = Color(0xFF4A00E0))
                    }
                }

                // Bottom 50% - Client control grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1E1E1E))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "RECEIVER CONTROLS",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                triggerSolverCapture("MCQ")
                            },
                            enabled = !isProcessing.value,
                            modifier = Modifier.weight(1f).height(80.dp).padding(8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2DE2))
                        ) {
                            Text("Solve MCQ", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        Button(
                            onClick = {
                                triggerSolverCapture("CODE")
                            },
                            enabled = !isProcessing.value,
                            modifier = Modifier.weight(1f).height(80.dp).padding(8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0))
                        ) {
                            Text("Solve Code", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }

                    Button(
                        onClick = {
                            webRtcManager?.sendDataMessage("TOGGLE_FLASH")
                        },
                        modifier = Modifier.fillMaxWidth().height(60.dp).padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Toggle Flash", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
            this,
            eglBase.eglBaseContext,
            onIceCandidateGenerated = { candidate ->
                signalingClient?.sendIceCandidate(roomId, false, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            },
            onRemoteStreamReceived = { track ->
                remoteVideoTrack.value = track
                isConnected.value = true
            },
            onDataChannelMessage = { message ->
                handleControlMessage(message)
            }
        )

        webRtcManager?.createPeerConnection(isCameraPhone = false)

        // Observe Offer SDP
        lifecycleScope.launch {
            signalingClient?.observeOffer(roomId)?.collectLatest { offerSdp ->
                webRtcManager?.handleOffer(offerSdp) { answerDesc ->
                    signalingClient?.sendAnswer(roomId, answerDesc.description)
                }
            }
        }

        // Observe Remote Server ICE candidates
        lifecycleScope.launch {
            signalingClient?.observeIceCandidates(roomId)?.collectLatest { map ->
                val isCamera = map["isCamera"] as? Boolean ?: false
                if (isCamera) {
                    val sdp = map["sdp"] as String
                    val sdpMid = map["sdpMid"] as String
                    val sdpMLineIndex = (map["sdpMLineIndex"] as Long).toInt()
                    webRtcManager?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
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
                expectedImageSize = message.substringAfter("START_IMG:").toInt()
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
                // Allow network buffer breathing room
                kotlinx.coroutines.delay(12)
            }
            webRtcManager?.sendDataMessage("END_IMG")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            val room = uri.getQueryParameter("room")
            if (!room.isNullOrEmpty()) {
                localRoomId.value = room
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
