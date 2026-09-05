package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)
data class ChatMessage(val id: String, val text: String, val timestamp: Long, val isSent: Boolean)

enum class CallState { IDLE, CALLING, RINGING, CONNECTED }

object Bus {
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val connectedPeer = MutableStateFlow<Peer?>(null)
    
    val callState = MutableStateFlow(CallState.IDLE)
    val activeCallPeer = MutableStateFlow<Peer?>(null)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
    val earpiece = MutableStateFlow(false) // FIXED

    val currentChatPeer = MutableStateFlow<Peer?>(null)
    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
}
