# System Architecture: Secure Dual-Device AI Camera & Solver
## 1. System Overview
A local network (Wi-Fi/Hotspot) peer-to-peer surveillance and AI-solving system built strictly with native Android components to ensure rapid compilation and minimal binary size.
- **Device A (Server/Camera)**: Captures live video and raw high-res images. Hosted via a Android `ForegroundService` to bypass background restrictions.
- **Device B (Client/Viewer)**: Renders the live stream, sends remote triggers via TCP, and bridges image data to the Google App via Intents.

## 2. Technology Stack & Dev Environment
- **Environment**: Linux (Xubuntu), optimized for 16GB RAM. 
  - *Constraint*: Gradle must be configured with `org.gradle.jvmargs=-Xmx4096m` and `org.gradle.vfs.watch=true`.
  - *Constraint*: No NDK, C++, or heavy on-device ML libraries (e.g., MediaPipe/ExecuTorch) to prevent CPU bottlenecking during builds.
- **Language**: Kotlin (JDK 21)
- **UI Framework**: Jetpack Compose (Material 3)
- **Camera Core**: CameraX API (`ProcessCameraProvider`)
- **Networking**: Ktor Network (UDP for Stream, TCP for Commands)
- **AI Bridge**: Android Intent System (`ACTION_SEND` -> Google App/Lens)

## 3. Data Flow Pipelines

### A. The Secure Handshake & Command Channel
1. **Server** starts Ktor `ServerSocket` on `0.0.0.0:8080`.
2. **Client** connects to Server's Local IP via Ktor `aSocket`.
3. Commands (e.g., `CAPTURE_HQ`) are sent over this persistent TCP connection using `Dispatchers.IO`.

### B. Live Video Stream (Low Latency)
- **Format**: MJPEG (Motion JPEG).
- **Transport**: **UDP** to prevent head-of-line blocking on dropped frames.
- **Resolution**: 480p at 15 FPS (Bandwidth optimized).
- **Flow**: `Sensor -> ImageAnalysis -> YUV to JPEG -> UDP Datagram -> Client Canvas`.

### C. The AI Solver Loop (The Intent Bridge)
1. **Client** sends TCP Command: `{"action": "CAPTURE_HQ"}`.
2. **Server** triggers `ImageCapture.takePicture()` (Flash: OFF).
3. **Server** compresses to JPEG (Quality 95) -> Writes to TCP Socket.
4. **Client** receives Bytes -> Saves to local `cacheDir`.
5. **Client** generates a `FileProvider` URI for the saved JPEG.
6. **Client** fires `Intent.ACTION_SEND` targeting `com.google.android.googlequicksearchbox`, passing the URI via `Intent.EXTRA_STREAM`. 
7. User completes the loop by pasting their query directly into Google Lens.

## 4. OS Compliance Constraints
- **Zero-Cloud Policy**: Direct P2P only.
- **Android Background Limits**: Device A must use `ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA` with a persistent notification to keep the camera active when the screen is locked.
- **Clipboard Limits**: The system clipboard cannot be written to while the app is in the background (Android 10+). Prompt handling must occur while Device B is 