package com.example.filelink.webrtc

import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.UUID

class WebRtcConnection(
    val connectionId: String = "dc_" + UUID.randomUUID().toString().replace("-", "").take(16),
    val remotePeerId: String,
    private val factory: PeerConnectionFactory,
    private val sendSignal: (to: String, payload: JSONObject) -> Unit,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "WebRtcConnection"
    }

    interface Listener {
        fun onChannelOpen(connection: WebRtcConnection)
        fun onTextMessage(connection: WebRtcConnection, text: String)
        fun onBinaryMessage(connection: WebRtcConnection, data: ByteArray)
        fun onBufferedAmountLow(connection: WebRtcConnection)
        fun onChannelClosed(connection: WebRtcConnection)
        fun onError(connection: WebRtcConnection, error: String)
    }

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isRemoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var isClosed = false

    val isChannelOpen: Boolean
        get() = dataChannel?.state() == DataChannel.State.OPEN

    val bufferedAmount: Long
        get() = dataChannel?.bufferedAmount() ?: 0L

    fun initConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (isClosed) return
                val candidateObj = JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                val payload = JSONObject().apply {
                    put("kind", "candidate")
                    put("connectionId", connectionId)
                    put("candidate", candidateObj)
                }
                sendSignal(remotePeerId, payload)
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.d(TAG, "Inbound DataChannel received: ${dc.label()}")
                dataChannel = dc
                wireDataChannel(dc)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $newState for $remotePeerId")
                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    listener.onError(this@WebRtcConnection, "ICE connection failed")
                    close()
                } else if (newState == PeerConnection.IceConnectionState.CLOSED) {
                    close()
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })
    }

    fun startAsOffer(label: String = connectionId, serialization: String = "binary") {
        initConnection()
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val dc = peerConnection?.createDataChannel(label, init) ?: return
        dataChannel = dc
        wireDataChannel(dc)

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val payload = JSONObject().apply {
                            put("kind", "offer")
                            put("connectionId", connectionId)
                            put("sdp", desc.description)
                            put("label", label)
                            put("serialization", serialization)
                            put("reliable", true)
                        }
                        sendSignal(remotePeerId, payload)
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        listener.onError(this@WebRtcConnection, "Failed to set local description: $error")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                listener.onError(this@WebRtcConnection, "Failed to create offer: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, org.webrtc.MediaConstraints())
    }

    fun handleOffer(offerSdp: String) {
        initConnection()
        val desc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingCandidates()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val payload = JSONObject().apply {
                                    put("kind", "answer")
                                    put("connectionId", connectionId)
                                    put("sdp", answerDesc.description)
                                }
                                sendSignal(remotePeerId, payload)
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(err: String?) {
                                listener.onError(this@WebRtcConnection, "Failed to set answer local SDP: $err")
                            }
                        }, answerDesc)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        listener.onError(this@WebRtcConnection, "Failed to create answer: $err")
                    }
                    override fun onSetFailure(p0: String?) {}
                }, org.webrtc.MediaConstraints())
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                listener.onError(this@WebRtcConnection, "Failed to set remote offer SDP: $err")
            }
        }, desc)
    }

    fun handleAnswer(answerSdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingCandidates()
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                listener.onError(this@WebRtcConnection, "Failed to set answer SDP: $err")
            }
        }, desc)
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        if (!isRemoteDescriptionSet) {
            pendingCandidates.add(candidate)
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    private fun drainPendingCandidates() {
        for (candidate in pendingCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        pendingCandidates.clear()
    }

    private fun wireDataChannel(dc: DataChannel) {
        // Set low threshold to 256KB for smooth chunk flow
        try {
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {
                    val current = dc.bufferedAmount()
                    if (current < 256 * 1024) {
                        listener.onBufferedAmountLow(this@WebRtcConnection)
                    }
                }

                override fun onStateChange() {
                    val state = dc.state()
                    Log.d(TAG, "DataChannel state changed: $state for $remotePeerId")
                    if (state == DataChannel.State.OPEN) {
                        listener.onChannelOpen(this@WebRtcConnection)
                    } else if (state == DataChannel.State.CLOSED) {
                        listener.onChannelClosed(this@WebRtcConnection)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    try {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        if (!buffer.binary) {
                            val text = String(bytes, Charsets.UTF_8)
                            listener.onTextMessage(this@WebRtcConnection, text)
                        } else {
                            listener.onBinaryMessage(this@WebRtcConnection, bytes)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing incoming data channel message", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register DataChannel observer", e)
        }
    }

    fun sendText(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.wrap(bytes)
            dc.send(DataChannel.Buffer(buffer, false))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text", e)
            false
        }
    }

    fun sendBinary(byteArray: ByteArray): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return try {
            val buffer = ByteBuffer.wrap(byteArray)
            dc.send(DataChannel.Buffer(buffer, true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send binary chunk", e)
            false
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true

        try {
            val payload = JSONObject().apply {
                put("kind", "close")
                put("connectionId", connectionId)
            }
            sendSignal(remotePeerId, payload)
        } catch (_: Exception) {}

        try { dataChannel?.close() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        dataChannel = null
        peerConnection = null
        listener.onChannelClosed(this)
    }
}
