package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)

enum class CallState { IDLE, CALLING, RINGING, CONNECTED }

object Bus {
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val callState = MutableStateFlow(CallState.IDLE)
    val activePeer = MutableStateFlow<Peer?>(null)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
}
