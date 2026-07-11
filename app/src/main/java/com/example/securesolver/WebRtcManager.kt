package com.example.securesolver

import android.content.Context
import android.view.Surface
import org.webrtc.*
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
    private var localSurface: Surface? = null
    private var dataChannel: DataChannel? = null

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

    fun initLocalVideoSource(): Surface {
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCameraX", eglBaseContext)
        localVideoSource = peerConnectionFactory.createVideoSource(false)
        
        surfaceTextureHelper?.startListening { videoFrame ->
            localVideoSource?.capturerObserver?.onFrameCaptured(videoFrame)
        }
        
        val surfaceTexture = surfaceTextureHelper!!.surfaceTexture
        surfaceTexture.setDefaultBufferSize(1280, 720)
        localSurface = Surface(surfaceTexture)
        
        localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", localVideoSource)
        return localSurface!!
    }

    fun createPeerConnection(isCameraPhone: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
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
            localVideoTrack?.let {
                peerConnection?.addTrack(it, listOf("ARDAMS"))
            }
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
        peerConnection?.close()
        surfaceTextureHelper?.stopListening()
        surfaceTextureHelper?.dispose()
        localVideoSource?.dispose()
    }
}
