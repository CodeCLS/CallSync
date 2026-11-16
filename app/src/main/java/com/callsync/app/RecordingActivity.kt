package com.callsync.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callsync.app.service.CallRecordingService
import com.callsync.app.service.RecorderState
import kotlinx.coroutines.delay

/** Full-screen live recording view, opened from the notification or the widget. */
class RecordingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RecordingScreen() }
            }
        }
    }

    @Composable
    private fun RecordingScreen() {
        val recording by RecorderState.recording.collectAsStateWithLifecycle()
        val paused by RecorderState.paused.collectAsStateWithLifecycle()
        val transcript by RecorderState.liveTranscript.collectAsStateWithLifecycle()
        val startedAt by RecorderState.startedAtMs.collectAsStateWithLifecycle()
        val active = recording || paused

        // Ticking elapsed-time string.
        val elapsed by produceState("00:00", startedAt, active) {
            while (true) {
                value = if (startedAt > 0 && active) {
                    val s = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
                    "%02d:%02d".format(s / 60, s % 60)
                } else "00:00"
                delay(1000)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (paused) Color(0xFFF9A825) else if (recording) Color(0xFFC62828) else Color.Gray,
                    modifier = Modifier.size(14.dp).clip(CircleShape),
                ) {}
                Text(
                    "  " + if (paused) "Paused" else if (recording) "Recording" else "Not recording",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(elapsed, fontSize = 48.sp, style = MaterialTheme.typography.displaySmall)

            Text("Live transcript", style = MaterialTheme.typography.labelLarge)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                val scroll = rememberScrollState()
                // Auto-scroll to the newest text.
                androidx.compose.runtime.LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }
                Text(
                    text = transcript.ifBlank {
                        if (active) "Listening…" else "No active recording."
                    },
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (active) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { send(CallRecordingService.ACTION_PAUSE_RESUME) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (paused) "Resume" else "Pause") }
                    Button(
                        onClick = { send(CallRecordingService.ACTION_STOP_SAVE); finish() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop & save") }
                }
                Button(
                    onClick = { send(CallRecordingService.ACTION_CANCEL); finish() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("Cancel (discard)") }
            } else {
                Text(
                    "Start a call to begin recording.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    private fun send(action: String) {
        startService(Intent(this, CallRecordingService::class.java).setAction(action))
    }
}
