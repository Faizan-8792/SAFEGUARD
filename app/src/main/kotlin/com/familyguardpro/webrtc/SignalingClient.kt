package com.familyguardpro.webrtc

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URI

/**
 * WebRTC Signaling Client for SDP and ICE candidate exchange
 */
class SignalingClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val streamType: String, // 'camera', 'screen', 'audio'
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "SignalingClient"
    }
    
    private var webSocket: WebSocketClient? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onRemoteSdp(sdp: SessionDescription)
        fun onRemoteIceCandidate(candidate: IceCandidate)
        fun onParentJoined()
        fun onParentLeft()
        fun onError(error: String)
    }
    
    fun connect() {
        val wsUrl = buildWsUrl()
        Log.d(TAG, "Connecting to signaling server: $wsUrl")
        
        webSocket = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Signaling connected")
                isConnected = true
                reconnectAttempts = 0
                listener.onConnected()
                
                // Send join message
                sendMessage(JSONObject().apply {
                    put("type", "join")
                    put("deviceId", deviceId)
                    put("streamType", streamType)
                    put("role", "sender")
                })
            }
            
            override fun onMessage(message: String?) {
                message?.let { handleMessage(it) }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Signaling closed: $reason (remote: $remote)")
                isConnected = false
                listener.onDisconnected()
                
                // Attempt reconnection if closed unexpectedly
                if (remote && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isConnected) {
                            connect()
                        }
                    }, 2000L * reconnectAttempts)
                }
            }
            
            override fun onError(ex: Exception?) {
                Log.e(TAG, "Signaling error", ex)
                listener.onError("Signaling error: ${ex?.message}")
            }
        }
        
        webSocket?.connect()
    }
    
    fun disconnect() {
        isConnected = false
        reconnectAttempts = maxReconnectAttempts // Prevent reconnection
        webSocket?.close()
        webSocket = null
    }
    
    fun sendOffer(sdp: SessionDescription) {
        sendMessage(JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp.description)
            put("deviceId", deviceId)
            put("streamType", streamType)
        })
    }
    
    fun sendAnswer(sdp: SessionDescription) {
        sendMessage(JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp.description)
            put("deviceId", deviceId)
            put("streamType", streamType)
        })
    }
    
    fun sendIceCandidate(candidate: IceCandidate) {
        sendMessage(JSONObject().apply {
            put("type", "ice_candidate")
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("deviceId", deviceId)
            put("streamType", streamType)
        })
    }
    
    fun sendStreamStarted() {
        sendMessage(JSONObject().apply {
            put("type", "stream_started")
            put("deviceId", deviceId)
            put("streamType", streamType)
        })
    }
    
    fun sendStreamStopped() {
        sendMessage(JSONObject().apply {
            put("type", "stream_stopped")
            put("deviceId", deviceId)
            put("streamType", streamType)
        })
    }
    
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            Log.d(TAG, "Received message: $type")
            
            when (type) {
                "answer" -> {
                    val sdp = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        json.getString("sdp")
                    )
                    listener.onRemoteSdp(sdp)
                }
                
                "offer" -> {
                    // Parent sent offer (for renegotiation)
                    val sdp = SessionDescription(
                        SessionDescription.Type.OFFER,
                        json.getString("sdp")
                    )
                    listener.onRemoteSdp(sdp)
                }
                
                "ice_candidate" -> {
                    val candidate = IceCandidate(
                        json.getString("sdpMid"),
                        json.getInt("sdpMLineIndex"),
                        json.getString("candidate")
                    )
                    listener.onRemoteIceCandidate(candidate)
                }
                
                "parent_joined" -> {
                    Log.d(TAG, "Parent joined the session")
                    listener.onParentJoined()
                }
                
                "parent_left" -> {
                    Log.d(TAG, "Parent left the session")
                    listener.onParentLeft()
                }
                
                "error" -> {
                    val error = json.optString("message", "Unknown error")
                    listener.onError(error)
                }
                
                "ping" -> {
                    // Respond to ping
                    sendMessage(JSONObject().apply {
                        put("type", "pong")
                    })
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $message", e)
        }
    }
    
    private fun sendMessage(json: JSONObject) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send message")
            return
        }
        
        try {
            webSocket?.send(json.toString())
            Log.d(TAG, "Sent message: ${json.optString("type")}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    private fun buildWsUrl(): String {
        val base = serverUrl
            .trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val sessionId = "${deviceId}_${streamType}_webrtc"
        return "$base/ws/webrtc?session=$sessionId&deviceId=$deviceId&type=$streamType&role=sender"
    }
}
