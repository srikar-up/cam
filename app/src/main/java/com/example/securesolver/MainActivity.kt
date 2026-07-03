package com.example.securesolver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.securesolver.ui.theme.SecureSolverTheme
import io.ktor.network.sockets.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val connectionManager = ConnectionManager()
    private lateinit var lensIntegrationEngine: LensIntegrationEngine
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isFlashEnabled = false
    private var activeClientSocket: Socket? = null

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
        lensIntegrationEngine = LensIntegrationEngine(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA)
            )
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
                text = "SECURE SOLVER",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "P2P Secure Camera & AI Solver",
                fontSize = 16.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            Button(
                onClick = { onRoleSelected("SERVER") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(16.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2DE2))
            ) {
                Text("HOST MODE (CAMERA)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onRoleSelected("CLIENT") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(16.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0))
            ) {
                Text("CLIENT MODE (VIEWER)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun ServerScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val localIp = remember { getLocalIpAddress() }
        var serverLogs by remember { mutableStateOf("Server started. Awaiting connections...") }
        
        LaunchedEffect(Unit) {
            lifecycleScope.launch(Dispatchers.IO) {
                connectionManager.startServer(8080) { socket, message ->
                    activeClientSocket = socket
                    withContext(Dispatchers.Main) {
                        serverLogs = "Client Connected! Command: $message"
                    }
                    when (message) {
                        "CAPTURE_MCQ", "CAPTURE_CODE" -> {
                            captureAndSendImage(socket)
                        }
                        "TOGGLE_FLASH" -> {
                            isFlashEnabled = !isFlashEnabled
                            camera?.cameraControl?.enableTorch(isFlashEnabled)
                            withContext(Dispatchers.Main) {
                                serverLogs = "Torch set to: $isFlashEnabled"
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Server IP: $localIp : 8080",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                val qrBitmap = remember { QrGenerator.generateQrCode("$localIp:8080") }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                Text(
                    text = serverLogs,
                    color = Color.Green,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    private fun captureAndSendImage(socket: Socket) {
        val capture = imageCapture ?: return
        val tempFile = File(cacheDir, "server_capture.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        val bytes = stream.toByteArray()
                        connectionManager.sendImage(socket, bytes)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            }
        )
    }

    @Composable
    fun ClientScreen() {
        var ipInput by remember { mutableStateOf("") }
        var isConnected by remember { mutableStateOf(false) }
        var clientSocket by remember { mutableStateOf<Socket?>(null) }
        var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isProcessing by remember { mutableStateOf(false) }
        var currentSolvingType by remember { mutableStateOf("") }

        LaunchedEffect(clientSocket) {
            val socket = clientSocket ?: return@LaunchedEffect
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val bytes = connectionManager.receiveImage(socket)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        withContext(Dispatchers.Main) {
                            capturedBitmap = bitmap
                            isProcessing = false
                            if (currentSolvingType == "MCQ") {
                                lensIntegrationEngine.solveMCQ(bitmap)
                            } else if (currentSolvingType == "CODE") {
                                lensIntegrationEngine.solveCode(bitmap)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        isConnected = false
                        Toast.makeText(this@MainActivity, "Connection Lost", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!isConnected) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Connect to Camera Server",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            label = { Text("Server IP Address") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4A00E0),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color.White,
                                unfocusedLabelColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                lifecycleScope.launch {
                                    try {
                                        val socket = connectionManager.connectToServer(ipInput, 8080)
                                        clientSocket = socket
                                        isConnected = true
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(this@MainActivity, "Connection Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("CONNECT")
                        }
                    }
                } else {
                    if (capturedBitmap != null) {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = "Live Feed",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = Color(0xFF4A00E0))
                    }
                }
            }

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
                    text = "CONTROLS",
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
                            val socket = clientSocket
                            if (socket != null && !isProcessing) {
                                isProcessing = true
                                currentSolvingType = "MCQ"
                                lifecycleScope.launch {
                                    connectionManager.sendMessage(socket, "CAPTURE_MCQ")
                                }
                            }
                        },
                        enabled = isConnected && !isProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2DE2))
                    ) {
                        Text("Solve MCQ", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }

                    Button(
                        onClick = {
                            val socket = clientSocket
                            if (socket != null && !isProcessing) {
                                isProcessing = true
                                currentSolvingType = "CODE"
                                lifecycleScope.launch {
                                    connectionManager.sendMessage(socket, "CAPTURE_CODE")
                                }
                            }
                        },
                        enabled = isConnected && !isProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A00E0))
                    ) {
                        Text("Solve Code", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }

                Button(
                    onClick = {
                        val socket = clientSocket
                        if (socket != null) {
                            lifecycleScope.launch {
                                connectionManager.sendMessage(socket, "TOGGLE_FLASH")
                            }
                        }
                    },
                    enabled = isConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Toggle Flash", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: ""
                        if (ip.isNotEmpty()) return ip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.close()
    }
}
