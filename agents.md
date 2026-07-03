# Agent Directives & Implementation Plan
<!-- Role: Staff Android Engineer | Focus: Security & Performance -->

## 1. Core Identity & Rules
- **Role**: You are an expert Kotlin Android developer specializing in low-level networking and CameraX.
- **Constraint**: Do NOT suggest Flutter, React Native, or WebRTC cloud servers. Use strictly Native Kotlin.
- **Constraint**: Ensure all networking code runs on `Dispatchers.IO` to prevent UI thread blocks.

## 2. Implementation Phases (Execution Order)

### Phase 1: The Network Skeleton (Ktor)
- **Task**: Create a `ConnectionManager` class.
- **Requirement**: Implement a `ServerSocket` listener on the Camera Device.
- **Requirement**: Implement a `ClientSocket` sender on the Viewer Device.
- **Validation**: Ensure text messages can be sent/received between two emulators.

### Phase 2: The CameraX Pipeline
- **Task**: Implement `CameraPreview` (ViewFinder) and `ImageCapture` use cases.
- **Optimization**: Bind the camera lifecycle to the Service, not the Activity, so it runs while the screen is off.
- **Critical Code**:
  ```kotlin
  val analyzer = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
  ```

### Phase 3: The Lens Bridge
- **Task**: Build the `LensIntegrationEngine`.
- **Logic**:
  1. Save `bitmap` to `FileProvider` path.
  2. Use `ClipboardManager` to set the system prompt.
  3. Construct the specific `Intent` for Google Lens.

### Phase 4: UI & Split-Screen
- **Task**: Build a Jetpack Compose UI.
- **Layout**:
  - Top 50%: Live Stream Canvas.
  - Bottom 50%: Control Grid ("Solve MCQ", "Solve Code", "Toggle Flash").

## 3. Tooling & Libraries
(Copy these into `libs.versions.toml`)
- `androidx.camera:camera-camera2:1.3.0`
- `androidx.camera:camera-lifecycle:1.3.0`
- `androidx.camera:camera-view:1.3.0`
- `io.ktor:ktor-network:2.3.7`
- `com.google.zxing:core:3.5.2` (For QR Handshake)

## 4. Definition of Done
- App A (Server) runs in background without crashing.
- App B (Client) receives video with <200ms latency.
- "Solve MCQ" button successfully opens Google Lens with the prompt pre-copied.
