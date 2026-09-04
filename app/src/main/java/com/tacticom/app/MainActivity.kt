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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tacticom.app.ui.TacticomTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Controller.startService(this)
        }
        if (missing.isEmpty()) Controller.startService(this) else launcher.launch(missing.toTypedArray())

        setContent {
            val dark by Bus.dark.collectAsState()
            TacticomTheme(dark = dark) {
                Root()
            }
        }
    }
}

@Composable
fun Root() {
    var tab by remember { mutableStateOf(0) }
    val inSession by Bus.inSession.collectAsState()
    LaunchedEffect(inSession) { if (inSession != null) tab = 1 }

    val items = listOf(
        "Lobby" to Icons.Default.Home,
        "Intercom" to Icons.Default.Mic,
        "Profile" to Icons.Default.Person
    )

    // Use screen width to determine Tablet vs Phone layout (stable API, no experimental flags)
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    if (!isTablet) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("TACTICOM", fontWeight = FontWeight.ExtraBold) }) },
            bottomBar = {
                NavigationBar {
                    items.forEachIndexed { i, p ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(p.second, null) },
                            label = { Text(p.first) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) { Screen(tab) }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                items.forEachIndexed { i, p ->
                    NavigationRailItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(p.second, null) },
                        label = { Text(p.first) }
                    )
                }
            }
            Box(Modifier.weight(1f).padding(24.dp)) { Screen(tab) }
        }
    }
}

@Composable
fun Screen(tab: Int) {
    when (tab) {
        0 -> LobbyScreen()
        1 -> IntercomScreen()
        else -> ProfileScreen()
    }
}

@Composable
fun LobbyScreen() {
    val peers by Bus.peers.collectAsState()
    val connected by Bus.connectedTo.collectAsState()
    val lobby by Bus.lobby.collectAsState()
    val toast by Bus.toastMsg.collectAsState()
    var newName by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<SessionInfo?>(null) }
    var passInput by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (toast.isNotEmpty()) {
            Text(toast, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (connected == null) {
            Text("DEVICES ON NETWORK", style = MaterialTheme.typography.labelMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("This device — ${Store.activeName()}", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { Controller.connectSelf() }) { Text("OPEN MY SESSIONS") }
                }
            }
            if (peers.isEmpty()) {
                Text("No other devices discovered yet.\nIf your router isolates subnets, add a manual IP in Profile.",
                    style = MaterialTheme.typography.bodySmall)
            }
            peers.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${p.ip}:${p.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { Controller.ring(p) }) {
                            Icon(Icons.Default.Notifications, "Ring")
                        }
                        Button(onClick = { Controller.connect(p) }) { Text("CONNECT") }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HOST: ${connected?.name}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { Controller.conn?.close() }) { Text("DISCONNECT") }
            }

            Text("OPEN SESSIONS", style = MaterialTheme.typography.labelMedium)
            if (lobby.isEmpty()) Text("No sessions on this host yet.", style = MaterialTheme.typography.bodySmall)
            lobby.forEach { s ->
                Card(Modifier.fillMaxWidth().clickable {
                    if (s.locked) { pending = s; passInput = "" } else Controller.joinSession(s, "")
                }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name + if (s.locked) " \uD83D\uDD12" else "", fontWeight = FontWeight.Bold)
                            Text("${s.count} online", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("START NEW SESSION", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(newName, { newName = it }, label = { Text("Session name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newPass, { newPass = it }, label = { Text("Access code (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { Controller.createSession(newName, newPass); newName = ""; newPass = "" },
                modifier = Modifier.fillMaxWidth()) { Text("START SESSION") }
        }
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Session: ${pending?.name}") },
            text = {
                OutlinedTextField(passInput, { passInput = it }, label = { Text("Access code") })
            },
            confirmButton = {
                TextButton(onClick = { pending?.let { Controller.joinSession(it, passInput) }; pending = null }) { Text("JOIN") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
fun IntercomScreen() {
    val session by Bus.inSession.collectAsState()
    val transmitting by Bus.transmitting.collectAsState()
    val live by Bus.liveMode.collectAsState()
    val ear by Bus.earpiece.collectAsState()
    val vu by Bus.vu.collectAsState()
    val presence by Bus.presence.collectAsState()
    val myName by Bus.mySessionName.collectAsState()

    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not in a session.\nJoin one from the Lobby.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(session!!.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text("as $myName • ${session!!.count} online", style = MaterialTheme.typography.bodySmall)
        Text(presence.joinToString(", "), style = MaterialTheme.typography.bodySmall)

        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth(fraction = vu.coerceIn(0f, 1f)).height(8.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CONSTANT LIVE", style = MaterialTheme.typography.labelMedium)
            Switch(checked = live, onCheckedChange = { Controller.setLive(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EARPIECE (CALL SPEAKER)", style = MaterialTheme.typography.labelMedium)
            Switch(checked = ear, onCheckedChange = { Controller.setEarpiece(it) })
        }

        Spacer(Modifier.height(8.dp))
        val color = if (transmitting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(color)
                .pointerInput(live) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (!live) Controller.setTransmit(true)
                        waitForUpOrCancellation()
                        if (!live) Controller.setTransmit(false)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (transmitting) "TRANSMITTING" else if (live) "LIVE" else "HOLD\nTO TALK",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleLarge
            )
        }

        OutlinedButton(onClick = { Controller.leave() }) { Text("LEAVE SESSION") }
    }
}

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val profiles = remember(refresh) { Store.profiles() }
    val active = remember(refresh) { Store.activeProfile() }
    var nameField by remember { mutableStateOf(active.name) }
    var ipField by remember { mutableStateOf("") }
    val dark by Bus.dark.collectAsState()

    val ringtonePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val list = Store.profiles().toMutableList()
            val idx = list.indexOfFirst { it.id == active.id }
            if (idx >= 0) {
                list[idx] = active.copy(ringtone = uri.toString())
                Store.saveProfiles(list)
                refresh++
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PROFILES", style = MaterialTheme.typography.labelMedium)
        profiles.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable {
                Store.activeProfileId = p.id
                refresh++
            }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.id == active.id) "● " else "○ ", color = MaterialTheme.colorScheme.primary)
                    Text(p.name, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nameField, { nameField = it }, label = { Text("Rename active") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                val list = Store.profiles().toMutableList()
                val idx = list.indexOfFirst { it.id == active.id }
                if (idx >= 0 && nameField.isNotBlank()) {
                    list[idx] = active.copy(name = nameField.trim().take(24))
                    Store.saveProfiles(list)
                    refresh++
                }
            }) { Text("SAVE") }
        }
        OutlinedButton(onClick = {
            val list = Store.profiles().toMutableList()
            val p = Profile(java.util.UUID.randomUUID().toString().take(8), "OP-" + (1000..9999).random(), null)
            list.add(p)
            Store.saveProfiles(list)
            Store.activeProfileId = p.id
            refresh++
        }) { Text("ADD PROFILE") }

        Spacer(Modifier.height(8.dp))
        Text("RINGTONE", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { ringtonePicker.launch("audio/*") }) {
            Text(if (active.ringtone != null) "CHANGE RINGTONE ✓" else "CHOOSE RINGTONE")
        }

        Spacer(Modifier.height(8.dp))
        Text("APPEARANCE", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DARK MODE (default)", style = MaterialTheme.typography.labelMedium)
            Switch(checked = dark, onCheckedChange = {
                Bus.dark.value = it
                Store.themeDark = it
            })
        }

        Spacer(Modifier.height(8.dp))
        Text("MANUAL IPs (subnet fallback)", style = MaterialTheme.typography.labelMedium)
        Store.manualIps.forEach { ip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ip, modifier = Modifier.weight(1f))
                TextButton(onClick = { Store.manualIps = Store.manualIps - ip }) { Text("REMOVE") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ipField, { ipField = it }, label = { Text("e.g. 192.168.0.25") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                val ip = ipField.trim()
                if (ip.isNotEmpty()) { Store.manualIps = Store.manualIps + ip; ipField = "" }
            }) { Text("ADD") }
        }
    }
}
