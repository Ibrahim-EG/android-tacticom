package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)

object Bus {
    val status = MutableStateFlow("STARTING")
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val activeCall = MutableStateFlow<Peer?>(null)
    val isInCall = MutableStateFlow(false)
    val transmitting = MutableStateFlow(false)
    val receivingAudio = MutableStateFlow(false)
    val liveMode = MutableStateFlow(false)
    val earpiece = MutableStateFlow(false)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
}
