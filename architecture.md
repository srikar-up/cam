# System Architecture: Secure Dual-Device AI Camera & Solver
<!-- Architecture Version: 1.0 | Stack: Native Android (Kotlin) -->

## 1. System Overview
A peer-to-peer surveillance and AI-solving system operating on a local network (Wi-Fi/Hotspot/USB).
- **Device A (Server/Camera)**: Captures live video and raw high-res images. Hosted on a background service.
- **Device B (Client/Viewer)**: Renders live stream, sends remote triggers, and bridges image data to Google Lens/Gemini.

## 2. Technology Stack
- **Language**: Kotlin (JDK 21)
- **UI Framework**: Jetpack Compose (Material 3)
- **Camera Core**: CameraX API (ProcessCameraProvider)
- **Networking**: Ktor Network (Raw TCP/UDP Sockets) + TLS
- **AI Bridge**: Android Intent System (Share Sheet -> Google Lens)
- **Security**: ECDH Key Exchange or Pre-Shared Key (QR Code)

## 3. Data Flow Pipelines

### A. The Secure Handshake (Session Init)
1. **Server** generates `SessionToken` + `ServerIP`.
2. **Server** displays encoded QR Code.
3. **Client** scans QR -> Extracts `IP` & `Token`.
4. **Client** opens SecureSocket(IP, Port).
5. **Server** validates `Token`. If valid -> `ConnectionAuthorized`.

### B. Live Video Stream (Low Latency)
- **Format**: MJPEG or Raw H.264 NAL units.
- **Transport**: UDP (Unordered) or TCP (if USB tethered).
- **Resolution**: 480p/720p (Bandwidth optimized).
- **Flow**: `Sensor -> Analyzer -> ByteStream -> Network -> Client SurfaceView`.

### C. The AI Solver Loop (High Latency, High Fidelity)
1. **Client** sends Command: `{"action": "CAPTURE_HQ"}`.
2. **Server** triggers `ImageCapture.takePicture()` (Flash Mode: OFF).
3. **Server** compresses to JPEG (Quality 100) -> Writes to Socket.
4. **Client** receives Bytes -> Saves to `CacheDir`.
5. **Client** Auto-Action:
   - **Scenario A (MCQ)**: Copy prompt "Solve MCQ: Option & Explanation" to Clipboard.
   - **Scenario B (Code)**: Copy prompt "Solve Code: Complexity & Solution" to Clipboard.
6. **Client** fires `Intent(ACTION_SEND)` -> Targets `com.google.android.googlequicksearchbox`.

## 4. Security Constraints
- **Zero-Cloud Policy**: No intermediate servers (Firebase/AWS) for video. Direct P2P only.
- **Local Authentication**: Server rejects all non-whitelisted IPs after pairing.
- **Encryption**: TLS 1.3 for command channel.
