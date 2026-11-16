package com.callsync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.callsync.app.data.AppSettings
import com.callsync.app.sync.NotionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callsync.app.data.CallHistoryStore
import com.callsync.app.data.CallRecord
import com.callsync.app.data.CallStatus
import com.callsync.app.service.CallRecordingService
import com.callsync.app.service.RecorderState
import com.callsync.app.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.warmUp(applicationContext)
        com.callsync.app.sync.SyncWorker.enqueuePending(applicationContext)
        if (hasPhonePermission()) {
            if (ShizukuManager.hasPermission()) ShizukuManager.bind()
            CallRecordingService.start(this)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    var showOnboarding by remember { mutableStateOf(!AppSettings.isOnboarded(this)) }
                    if (showOnboarding) {
                        OnboardingScreen(onDone = { showOnboarding = false })
                    } else if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onEditAccount = { showOnboarding = true },
                        )
                    } else {
                        HomeScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }

    // ---- Onboarding: name + Notion + Deepgram ------------------------------

    @Composable
    private fun OnboardingScreen(onDone: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val editing = AppSettings.isOnboarded(context)
        var name by remember { mutableStateOf(AppSettings.userName(context)) }
        var token by remember { mutableStateOf(if (editing) AppSettings.notionToken(context) else "") }
        var database by remember { mutableStateOf(if (editing) AppSettings.notionDatabaseId(context) else "") }
        var deepgram by remember { mutableStateOf(if (editing) AppSettings.deepgramKey(context) else "") }
        var checking by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasPhonePermission()) CallRecordingService.start(this@MainActivity)
            onDone()
        }

        fun finish() {
            AppSettings.save(context, name, token, database, deepgram)
            if (editing) onDone() else permissions.launch(requiredPermissions())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (editing) "Account & Notion" else "Welcome to CallSync",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "When a call starts you'll get a notification asking whether to record it. " +
                    "Recorded calls are transcribed and saved as a page in your Notion database.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("1 · Your name", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("2 · Connect Notion", style = MaterialTheme.typography.titleMedium)
            Text(
                "a) Create an internal integration and copy its secret.\n" +
                    "b) Open the database calls should go into → ••• → Connections → add the integration.\n" +
                    "c) Copy the database link (Share → Copy link) and paste it below.\n" +
                    "The database needs a \"Name\" title and a \"Date\" property.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { openUrl("https://www.notion.so/profile/integrations") }) {
                Text("Open Notion integrations ↗")
            }
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Integration secret (ntn_…)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = database, onValueChange = { database = it },
                label = { Text("Database link or ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("3 · Transcription (Deepgram)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Calls are transcribed with Deepgram (German + English). A free account " +
                    "includes credit for many hours of calls.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { openUrl("https://console.deepgram.com/signup") }) {
                Text("Get a Deepgram API key ↗")
            }
            OutlinedTextField(
                value = deepgram, onValueChange = { deepgram = it },
                label = { Text("Deepgram API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text("⚠ $it", color = RED, style = MaterialTheme.typography.bodySmall) }

            Button(
                enabled = !checking && name.isNotBlank() && token.isNotBlank() &&
                    database.isNotBlank() && deepgram.isNotBlank(),
                onClick = {
                    checking = true
                    error = null
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            NotionClient(
                                token = token.trim(),
                                databaseId = AppSettings.parseDatabaseId(database),
                            ).verify()
                        }
                        checking = false
                        if (problem == null) finish() else error = problem
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) "Checking Notion…" else "Test connection & continue") }

            if (error != null) {
                TextButton(onClick = { finish() }) { Text("Save anyway") }
            }
            if (editing) {
                TextButton(onClick = onDone) { Text("Cancel") }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- Home: compact status + past calls --------------------------------

    @Composable
    private fun HomeScreen(onOpenSettings: () -> Unit) {
        val context = LocalContext.current
        var history by remember { mutableStateOf(CallHistoryStore.all(context)) }
        // Refresh the list periodically so newly-synced calls show up.
        LaunchedEffect(Unit) {
            while (true) {
                history = CallHistoryStore.all(context)
                delay(3000)
            }
        }

        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = onOpenSettings, shape = CircleShape) {
                    Text("⚙", style = MaterialTheme.typography.headlineSmall)
                }
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    "CallSync",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                )

                CompactStatus()

                Spacer(Modifier.size(16.dp))
                Text("Past calls", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))

                if (history.isEmpty()) {
                    Text(
                        "No calls tracked yet. Once a call is recorded and synced to " +
                            "Notion, it appears here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Leave room so the FAB never covers the last row.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                    ) {
                        items(history) { rec -> CallRow(rec) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CallRow(rec: CallRecord) {
        val context = LocalContext.current
        val clickable = !rec.notionUrl.isNullOrBlank()
        Card(
            modifier = Modifier.fillMaxWidth().let {
                if (clickable) it.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(rec.notionUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else it
            },
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        directionArrow(rec) + "  " + rec.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(0.dp).weight(1f))
                    when (rec.status) {
                        CallStatus.NO_ANSWER ->
                            Text("no answer", style = MaterialTheme.typography.labelSmall, color = AMBER)
                        CallStatus.PERSONAL ->
                            Text("personal · private", style = MaterialTheme.typography.labelSmall, color = GREY)
                        CallStatus.SYNCED -> {}
                    }
                }
                Spacer(Modifier.size(2.dp))
                val dur = if (rec.status == CallStatus.NO_ANSWER) "" else " · ${formatDuration(rec.durationSeconds)}"
                Text(
                    "${formatWhen(rec.startedAtEpochMs)}$dur",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (rec.number != rec.displayName) {
                    Text(rec.number, style = MaterialTheme.typography.bodySmall)
                }
                if (clickable) {
                    Text("Open in Notion ↗", style = MaterialTheme.typography.labelSmall, color = BLUE)
                }
            }
        }
    }

    @Composable
    private fun CompactStatus() {
        val recording by RecorderState.recording.collectAsStateWithLifecycle()
        val serviceRunning by RecorderState.serviceRunning.collectAsStateWithLifecycle()
        val listening by RecorderState.listeningForCalls.collectAsStateWithLifecycle()
        // Shared readiness flag maintained by ShizukuManager + the service
        // watchdog — no more per-screen binder polling that lagged ~1s behind.
        val recorderBound by RecorderState.recorderReady.collectAsStateWithLifecycle()

        // Nudge a re-check the moment this screen is shown so a stale "not ready"
        // repairs immediately instead of waiting for the next watchdog tick.
        LaunchedEffect(Unit) { ShizukuManager.ensureBound() }

        val ready = serviceRunning && listening && recorderBound
        val (label, color) = when {
            recording -> "RECORDING NOW" to GREEN
            ready -> (if (AppSettings.askBeforeRecording(this)) "Armed — you'll be asked on each call"
                else "Armed — calls auto-record") to GREEN
            else -> "Not ready — open Settings" to RED
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = color, modifier = Modifier.size(14.dp).clip(CircleShape)) {}
                Text("   $label", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    // ---- Settings: access + setup (the former main screen) ----------------

    @Composable
    private fun SettingsScreen(onBack: () -> Unit, onEditAccount: () -> Unit) {
        var hint by remember { mutableStateOf("") }
        var askFirst by remember { mutableStateOf(AppSettings.askBeforeRecording(this)) }

        val runtimePerms = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasPhonePermission()) {
                if (ShizukuManager.hasPermission()) ShizukuManager.bind()
                CallRecordingService.start(this@MainActivity)
                hint = "Watcher started"
            } else hint = "Phone + mic permission are required"
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Settings & access", style = MaterialTheme.typography.headlineSmall)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ask before recording", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (askFirst) "Each call shows a Record / Don't record notification"
                                else "Every call records automatically",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = askFirst, onCheckedChange = {
                            askFirst = it
                            AppSettings.setAskBeforeRecording(this@MainActivity, it)
                        })
                    }
                    val who = AppSettings.userName(this@MainActivity).ifBlank { "not set" }
                    Text("Signed in as $who", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onEditAccount) { Text("Edit name, Notion & Deepgram") }
                }
            }

            StatusPanel()

            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }

            Text("Setup (do once):", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { runtimePerms.launch(requiredPermissions()) }) {
                Text("1 · Grant app permissions")
            }
            Button(onClick = {
                ShizukuManager.requestPermission { granted ->
                    hint = if (granted) "Shizuku granted" else ShizukuManager.diagnose(applicationContext)
                    if (granted) {
                        ShizukuManager.bind()
                        CallRecordingService.start(this@MainActivity)
                    }
                }
            }) { Text("2 · Grant Shizuku") }

            Text(
                "Once both are green above, CallSync is armed for every call. " +
                    "Buttons below are a manual override.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                CallRecordingService.start(this@MainActivity)
                hint = "Watcher started"
            }) { Text("Start watcher") }
            Button(onClick = {
                CallRecordingService.stop(this@MainActivity)
                hint = "Watcher stopped"
            }) { Text("Stop watcher") }
        }
    }

    /** Full status card, refreshed once a second. */
    @Composable
    private fun StatusPanel() {
        val recording by RecorderState.recording.collectAsStateWithLifecycle()
        val serviceRunning by RecorderState.serviceRunning.collectAsStateWithLifecycle()
        val listening by RecorderState.listeningForCalls.collectAsStateWithLifecycle()
        val lastEvent by RecorderState.lastEvent.collectAsStateWithLifecycle()

        var shizukuReady by remember { mutableStateOf(false) }
        var recorderBound by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                shizukuReady = ShizukuManager.isShizukuReady() && ShizukuManager.hasPermission()
                recorderBound = ShizukuManager.recorder != null
                delay(1000)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ready = serviceRunning && listening && recorderBound
                StatusRow(
                    if (recording) "RECORDING NOW" else if (ready) "Armed — calls auto-record" else "Not ready",
                    if (recording || ready) GREEN else RED,
                    big = true,
                )
                StatusRow("Watcher service running", if (serviceRunning) GREEN else RED, ok = serviceRunning)
                StatusRow("Listening for calls", if (listening) GREEN else RED, ok = listening)
                StatusRow("Shizuku ready + granted", if (shizukuReady) GREEN else RED, ok = shizukuReady)
                StatusRow("Recorder connected", if (recorderBound) GREEN else RED, ok = recorderBound)
                Text("Last: $lastEvent", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, color: Color, ok: Boolean = true, big: Boolean = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = color,
                modifier = Modifier.size(if (big) 14.dp else 10.dp).clip(CircleShape),
            ) {}
            Text(
                "  ${if (!big) (if (ok) "✓ " else "✗ ") else ""}$label",
                style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            )
        }
    }

    private fun directionArrow(rec: CallRecord): String = when (rec.direction) {
        com.callsync.app.data.CallDirection.INCOMING -> "↓"
        com.callsync.app.data.CallDirection.OUTGOING -> "↑"
        com.callsync.app.data.CallDirection.UNKNOWN -> "•"
    }

    private fun formatWhen(epochMs: Long): String =
        SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun hasPhonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    companion object {
        private val GREEN = Color(0xFF2E7D32)
        private val RED = Color(0xFFC62828)
        private val AMBER = Color(0xFFB26A00)
        private val BLUE = Color(0xFF1565C0)
        private val GREY = Color(0xFF6B6B6B)
    }
}
