package com.example.securesolver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.view.Surface
import org.webrtc.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class WebRtcManager(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onRemoteStreamReceived: (VideoTrack) -> Unit,
    private val onDataChannelMessage: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null
    
    // Built-in WebRTC camera capturer
    private var videoCapturer: CameraVideoCapturer? = null

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try to find back-facing camera first
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        // Fallback to front-facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        return null
    }

    // Initialize native WebRTC Camera2Capturer stream
    fun initLocalVideoSource(): VideoTrack? {
        val capturer = createCameraCapturer() ?: return null
        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCamera", eglBaseContext)
        localVideoSource = peerConnectionFactory.createVideoSource(false)
        
        capturer.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", localVideoSource)
        localVideoTrack?.setEnabled(true)
        return localVideoTrack
    }

    // Capture standard high-res video frame directly from local video track
    fun captureCurrentFrame(onBitmapCaptured: (Bitmap) -> Unit) {
        val track = localVideoTrack ?: return
        val sink = object : VideoSink {
            private var captured = false
            override fun onFrame(frame: VideoFrame) {
                if (captured) return
                captured = true
                
                try {
                    val bitmap = videoFrameToBitmap(frame)
                    onBitmapCaptured(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    track.removeSink(this)
                }
            }
        }
        track.addSink(sink)
    }

    private fun videoFrameToBitmap(frame: VideoFrame): Bitmap {
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        
        // Convert input frame buffer to I420 format
        val i420 = buffer.toI420() ?: throw IllegalStateException("Failed to convert buffer to I420")
        val yuvBytes = ByteArray(width * height * 3 / 2)
        
        var offset = 0
        val yBuffer = i420.dataY
        val uBuffer = i420.dataU
        val vBuffer = i420.dataV
        
        val yStride = i420.strideY
        val uStride = i420.strideU
        val vStride = i420.strideV
        
        // Copy Y plane
        for (row in 0 until height) {
            yBuffer.position(row * yStride)
            yBuffer.get(yuvBytes, offset, width)
            offset += width
        }
        
        // Copy U & V planes (Chroma planes are half resolution)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        
        for (row in 0 until chromaHeight) {
            uBuffer.position(row * uStride)
            uBuffer.get(yuvBytes, offset, chromaWidth)
            offset += chromaWidth
        }
        
        for (row in 0 until chromaHeight) {
            vBuffer.position(row * vStride)
            vBuffer.get(yuvBytes, offset, chromaWidth)
            offset += chromaWidth
        }
        
        i420.release()
        
        // Interleave U and V planes to form standard NV21 format
        val nv21Bytes = ByteArray(width * height * 3 / 2)
        System.arraycopy(yuvBytes, 0, nv21Bytes, 0, width * height)
        
        var uIndex = width * height
        var vIndex = width * height + (width * height / 4)
        var nv21Index = width * height
        
        for (i in 0 until (width * height / 4)) {
            nv21Bytes[nv21Index] = yuvBytes[vIndex + i]
            nv21Bytes[nv21Index + 1] = yuvBytes[uIndex + i]
            nv21Index += 2
        }
        
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun createPeerConnection(isCameraPhone: Boolean) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.services.mozilla.com").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.xten.com").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    onConnectionStateChanged(true)
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                           state == PeerConnection.IceConnectionState.FAILED) {
                    onConnectionStateChanged(false)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidateGenerated(candidate)
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            
            override fun onAddStream(stream: MediaStream) {
                if (stream.videoTracks.isNotEmpty()) {
                    onRemoteStreamReceived(stream.videoTracks[0])
                }
            }
            
            override fun onRemoveStream(stream: MediaStream) {}
            
            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(amount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val data = buffer.data
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        onDataChannelMessage(String(bytes))
                    }
                })
            }
            
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    onRemoteStreamReceived(track)
                }
            }
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)

        if (isCameraPhone) {
            addLocalVideoTrackToConnection()
            val dcInit = DataChannel.Init()
            dataChannel = peerConnection?.createDataChannel("control", dcInit)
            dataChannel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    onDataChannelMessage(String(bytes))
                }
            })
        }
    }

    fun addLocalVideoTrackToConnection() {
        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("ARDAMS"))
        }
    }

    fun createOffer(onSdpCreated: (SessionDescription) -> Unit) {
        val sdpObserver = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        onSdpCreated(desc)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }
        peerConnection?.createOffer(sdpObserver, MediaConstraints())
    }

    fun handleOffer(offerSdp: String, onAnswerCreated: (SessionDescription) -> Unit) {
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                createAnswer(onAnswerCreated)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, offerDesc)
    }

    private fun createAnswer(onAnswerCreated: (SessionDescription) -> Unit) {
        val sdpObserver = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        onAnswerCreated(desc)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }
        peerConnection?.createAnswer(sdpObserver, MediaConstraints())
    }

    fun handleAnswer(answerSdp: String) {
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, answerDesc)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendDataMessage(message: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = ByteBuffer.wrap(message.toByteArray())
        return dc.send(DataChannel.Buffer(buffer, false))
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        videoCapturer = null
        peerConnection?.close()
        surfaceTextureHelper?.stopListening()
        surfaceTextureHelper?.dispose()
        localVideoSource?.dispose()
    }
}
