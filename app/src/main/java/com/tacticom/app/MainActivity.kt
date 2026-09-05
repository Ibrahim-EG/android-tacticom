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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var screen by remember { mutableStateOf("lobby") }
    var pendingSession by remember { mutableStateOf<Session?>(null) }
    var passInput by remember { mutableStateOf("") }
    val currentSession by Bus.currentSession.collectAsState()

    LaunchedEffect(currentSession) {
        if (currentSession != null && screen == "lobby") screen = "session"
        else if (currentSession == null && screen == "session") screen = "lobby"
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            "lobby" -> LobbyScreen(
                onPeerClick = { peer -> Controller.callPeer(peer) },
                onSessionClick = { session ->
                    if (session.locked) { pendingSession = session; passInput = "" } 
                    else Controller.joinSession(session, null)
                },
                onSettingsClick = { screen = "settings" }
            )
            "session" -> SessionScreen(onLeave = { Controller.leaveSession() })
            "settings" -> SettingsScreen(onBack = { screen = "lobby" })
        }

        if (pendingSession != null) {
            AlertDialog(
                onDismissRequest = { pendingSession = null },
                title = { Text("Password Required") },
                text = {
                    OutlinedTextField(
                        value = passInput, onValueChange = { passInput = it },
                        label = { Text("Enter Password") }, modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingSession?.let { Controller.joinSession(it, passInput) }
                        pendingSession = null
                    }) { Text("JOIN") }
                },
                dismissButton = { TextButton(onClick = { pendingSession = null }) { Text("CANCEL") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(onPeerClick: (Peer) -> Unit, onSessionClick: (Session) -> Unit, onSettingsClick: () -> Unit) {
    val peers by Bus.peers.collectAsState()
    val sessions by Bus.sessions.collectAsState()
    val toast by Bus.toastMsg.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("TACTICOM", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
            actions = { IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (toast.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(toast, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Your Device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(Store.activeName(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Text("DEVICES ON NETWORK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (peers.isEmpty()) Text("Searching...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            peers.forEach { peer ->
                Card(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), onClick = { onPeerClick(peer) }) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(peer.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("RING", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                Text("ACTIVE SESSIONS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                sessions.forEach { session ->
                    Card(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp), onClick = { onSessionClick(session) }) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                                if (session.locked) Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(24.dp))
                                else Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(session.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Host: ${session.hostName} • ${session.memberCount} inside", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                            }
                            Text("JOIN", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(onLeave: () -> Unit) {
    val session by Bus.currentSession.collectAsState()
    val members by Bus.sessionMembers.collectAsState()
    val isTransmitting by Bus.isTransmitting.collectAsState()
    val vu by Bus.vu.collectAsState()

    val vuAnimated by animateFloatAsState(targetValue = vu, animationSpec = tween(durationMillis = 100), label = "vu")

    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant)))) {
        TopAppBar(
            title = { Text(session?.name ?: "Session") },
            navigationIcon = { IconButton(onClick = onLeave) { Icon(Icons.Default.ArrowBack, contentDescription = "Leave") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 16.dp)) {
                Text("MEMBERS IN SESSION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (members.isEmpty()) Text("Waiting for others...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        members.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Audio Level", fontWeight = FontWeight.SemiBold)
                            Text("${(vuAnimated * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(modifier = Modifier.fillMaxWidth(fraction = vuAnimated.coerceIn(0f, 1f)).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))))
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 32.dp)) {
                Box(
                    modifier = Modifier.size(200.dp).shadow(elevation = if (isTransmitting) 16.dp else 8.dp, shape = CircleShape)
                        .clip(CircleShape).background(Brush.radialGradient(colors = if (isTransmitting) listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                Controller.startTransmit()
                                waitForUpOrCancellation()
                                Controller.stopTransmit()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(if (isTransmitting) "TALKING" else "HOLD TO\nTALK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current; var refresh by remember { mutableStateOf(0) }; val active = remember(refresh) { Store.activeProfile() }; var nameField by remember { mutableStateOf(active.name) }; val dark by Bus.dark.collectAsState()
    val ringtonePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; val list = Store.profiles().toMutableList(); val idx = list.indexOfFirst { it.id == active.id }; if (idx >= 0) { list[idx] = active.copy(ringtone = uri.toString()); Store.saveProfiles(list); refresh++ } }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = nameField, onValueChange = { nameField = it }, label = { Text("Your Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { val list = Store.profiles().toMutableList(); val idx = list.indexOfFirst { it.id == active.id }; if (idx >= 0 && nameField.isNotBlank()) { list[idx] = active.copy(name = nameField.trim().take(24)); Store.saveProfiles(list); refresh++ } }, modifier = Modifier.fillMaxWidth()) { Text("Save Name") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Dark Mode", style = MaterialTheme.typography.bodyLarge); Switch(checked = dark, onCheckedChange = { Bus.dark.value = it; Store.themeDark = it }) }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Ringtone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = { ringtonePicker.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (active.ringtone != null) "Change Ringtone" else "Choose Ringtone") }
                }
            }
        }
    }
}
