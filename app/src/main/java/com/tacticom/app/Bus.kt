package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)

object Bus {
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val connectedPeer = MutableStateFlow<Peer?>(null)
    val isTransmitting = MutableStateFlow(false)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
    val earpiece = MutableStateFlow(false)
}
