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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Controller.startService(this) }
        if (missing.isEmpty()) Controller.startService(this) else launcher.launch(missing.toTypedArray())

        setContent {
            val dark by Bus.dark.collectAsState()
            TacticomTheme(dark = dark) { Root() }
        }
    }
}

@Composable
fun Root() {
    var tab by remember { mutableStateOf(0) }
    val items = listOf(
        "Devices" to Icons.Default.Home,
        "Intercom" to Icons.Default.Mic,
        "Settings" to Icons.Default.Person
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("TACTICOM", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold) }) },
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { i, p ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(p.second, null) }, label = { Text(p.first) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> DevicesScreen()
                1 -> IntercomScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun DevicesScreen() {
    val peers by Bus.peers.collectAsState()
    val activeCall by Bus.activeCall.collectAsState()
    val isInCall by Bus.isInCall.collectAsState()
    val toast by Bus.toastMsg.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toast.isNotEmpty()) Text(toast, color = MaterialTheme.colorScheme.error)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("YOUR DEVICE", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(Store.activeName(), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }

        // Replaced Card with Box to avoid Material3 compiler signature bugs
        if (isInCall && activeCall != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondary)
                    .padding(16.dp)
            ) {
                Text(
                    "IN CALL WITH ${activeCall!!.name.uppercase()} — OPEN INTERCOM TAB", 
                    color = MaterialTheme.colorScheme.onSecondary, 
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text("DEVICES ON NETWORK", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) Text("Searching for devices on your Wi‑Fi…", color = MaterialTheme.colorScheme.onSurfaceVariant)

        peers.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text(p.ip, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { Controller.ringPeer(p) }) { Icon(Icons.Default.Notifications, "Ring", tint = MaterialTheme.colorScheme.onSurface) }
                    Button(onClick = { Controller.callPeer(p) }) {
                        Icon(Icons.Default.Call, "Call")
                        Spacer(Modifier.size(8.dp))
                        Text("CALL")
                    }
                }
            }
        }
    }
}

@Composable
fun IntercomScreen() {
    val isInCall by Bus.isInCall.collectAsState()
    val activeCall by Bus.activeCall.collectAsState()
    val transmitting by Bus.transmitting.collectAsState()
    val live by Bus.liveMode.collectAsState()
    val ear by Bus.earpiece.collectAsState()
    val vu by Bus.vu.collectAsState()

    if (!isInCall || activeCall == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active call.\nGo to the Devices tab and tap CALL.", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("CONNECTED TO", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(activeCall!!.name.uppercase(), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)

        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction = vu.coerceIn(0f, 1f)).height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EARPIECE", color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = ear, onCheckedChange = { Controller.setEarpiece(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ALWAYS LIVE", color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = live, onCheckedChange = { Controller.setLive(it) })
        }

        Spacer(Modifier.height(16.dp))
        val color = if (transmitting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Box(
            Modifier.size(200.dp).clip(CircleShape).background(color).pointerInput(live) {
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
                if (transmitting) "TALKING" else if (live) "LIVE" else "HOLD\nTO TALK",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.headlineMedium
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { Controller.hangUp() }) { Text("HANG UP", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val active = remember(refresh) { Store.activeProfile() }
    var nameField by remember { mutableStateOf(active.name) }
    val dark by Bus.dark.collectAsState()

    val ringtonePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val list = Store.profiles().toMutableList()
            val idx = list.indexOfFirst { it.id == active.id }
            if (idx >= 0) {
                list[idx] = active.copy(ringtone = uri.toString())
                Store.saveProfiles(list)
                refresh++
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SETTINGS", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("YOUR NAME", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nameField, { nameField = it }, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface))
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

        Text("APPEARANCE", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DARK MODE", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Switch(checked = dark, onCheckedChange = { Bus.dark.value = it; Store.themeDark = it })
        }

        Text("RINGTONE", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { ringtonePicker.launch("audio/*") }) {
            Text(if (active.ringtone != null) "CHANGE RINGTONE" else "CHOOSE RINGTONE")
        }
    }
}
