@file:OptIn(ExperimentalMaterial3Api::class)
package com.tacticom.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tacticom.app.ui.TacticomTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Store.init(this)
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Controller.startService(this) }
        if (missing.isEmpty()) Controller.startService(this) else launcher.launch(missing.toTypedArray())
        setContent { val dark by Bus.dark.collectAsState(); TacticomTheme(dark = dark) { Root() } }
    }
}

@Composable
fun Root() {
    var tab by remember { mutableStateOf(0) }
    var inChat by remember { mutableStateOf(false) }
    val callState by Bus.callState.collectAsState()
    val items = listOf("Devices" to Icons.Default.Home, "Active Call" to Icons.Default.Mic, "Settings" to Icons.Default.Person)
    
    Box(Modifier.fillMaxSize()) {
        if (inChat) {
            ChatScreen(onBack = { 
                inChat = false
                Bus.currentChatPeer.value = null 
            })
        } else {
            Scaffold(
                topBar = { TopAppBar(title = { Text("TACTICOM", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold) }) },
                bottomBar = { NavigationBar { items.forEachIndexed { i, p -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(p.second, null) }, label = { Text(p.first) }) } } }
            ) { pad -> 
                Box(Modifier.padding(pad)) { 
                    when (tab) { 
                        0 -> DevicesScreen(onChatClick = { inChat = true })
                        1 -> IntercomScreen()
                        else -> SettingsScreen() 
                    } 
                } 
            }
        }
        
        if (callState == CallState.CALLING) CallingOverlay()
        if (callState == CallState.RINGING) IncomingCallOverlay()
    }
}

@Composable
fun ChatScreen(onBack: () -> Unit) {
    val peer by Bus.currentChatPeer.collectAsState()
    val messages by Bus.chatMessages.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(peer?.name ?: "Chat", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") }, maxLines = 3
                )
                Spacer(Modifier.size(8.dp))
                IconButton(onClick = { Controller.sendChat(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { msg -> ChatBubble(msg) }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isSent = msg.isSent
    val alignment = if (isSent) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier.widthIn(max = 280.dp).clip(MaterialTheme.shapes.medium).background(bgColor).padding(12.dp)
        ) { Text(msg.text, color = textColor) }
    }
}

@Composable
fun CallingOverlay() {
    val peer by Bus.activeCallPeer.collectAsState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("CALLING...", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium)
            Text(peer?.name ?: "Unknown", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { Controller.hangUp() }) { Text("CANCEL", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun IncomingCallOverlay() {
    val peer by Bus.activeCallPeer.collectAsState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
            Text("INCOMING CALL", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium)
            Text(peer?.name ?: "Unknown", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error).clickable { Controller.declineCall() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Decline", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(40.dp))
                    }
                    Text("Decline", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary).clickable { Controller.acceptCall() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Call, "Accept", tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(40.dp))
                    }
                    Text("Accept", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(onChatClick: () -> Unit) {
    val peers by Bus.peers.collectAsState()
    val callState by Bus.callState.collectAsState()
    val toast by Bus.toastMsg.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toast.isNotEmpty()) Text(toast, color = MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("YOUR DEVICE", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(Store.activeName(), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall) } }
        Text("DEVICES ON NETWORK", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) Text("Searching for devices...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        peers.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold); Text(p.ip, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { Controller.ringPeer(p) }) { Icon(Icons.Default.Notifications, "Ring", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = { Controller.openChat(p); onChatClick() }) { Icon(Icons.Default.Chat, "Chat", tint = MaterialTheme.colorScheme.primary) }
                    Button(onClick = { if (callState == CallState.IDLE) Controller.callPeer(p) }) { Icon(Icons.Default.Call, "Call"); Spacer(Modifier.size(8.dp)); Text("CALL") }
                }
            }
        }
    }
}

@Composable
fun IntercomScreen() {
    val callState by Bus.callState.collectAsState()
    val activePeer by Bus.activeCallPeer.collectAsState()
    val vu by Bus.vu.collectAsState()
    if (callState != CallState.CONNECTED) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No active call.", color = MaterialTheme.colorScheme.onBackground) }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("CONNECTED TO", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(activePeer?.name?.uppercase() ?: "UNKNOWN", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) { Box(Modifier.fillMaxWidth(fraction = vu.coerceIn(0f, 1f)).height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary)) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error).clickable { Controller.hangUp() }, contentAlignment = Alignment.Center) { Text("HANG UP", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current; var refresh by remember { mutableStateOf(0) }; val active = remember(refresh) { Store.activeProfile() }; var nameField by remember { mutableStateOf(active.name) }; val dark by Bus.dark.collectAsState()
    val ringtonePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; val list = Store.profiles().toMutableList(); val idx = list.indexOfFirst { it.id == active.id }; if (idx >= 0) { list[idx] = active.copy(ringtone = uri.toString()); Store.saveProfiles(list); refresh++ } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SETTINGS", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("YOUR NAME", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(nameField, { nameField = it }, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface)); Button(onClick = { val list = Store.profiles().toMutableList(); val idx = list.indexOfFirst { it.id == active.id }; if (idx >= 0 && nameField.isNotBlank()) { list[idx] = active.copy(name = nameField.trim().take(24)); Store.saveProfiles(list); refresh++ } }) { Text("SAVE") } }
        Text("APPEARANCE", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(verticalAlignment = Alignment.CenterVertically) { Text("DARK MODE", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = { Bus.dark.value = it; Store.themeDark = it }) }
        Text("RINGTONE", color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedButton(onClick = { ringtonePicker.launch("audio/*") }) { Text(if (active.ringtone != null) "CHANGE RINGTONE" else "CHOOSE RINGTONE") }
    }
}
