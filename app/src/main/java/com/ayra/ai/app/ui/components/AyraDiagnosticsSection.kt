package com.ayra.ai.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.security.AyraDiagnosticsEngine

@Composable
fun AyraDiagnosticsSection(context: Context) {
    val snapshot by AyraDiagnosticsEngine.snapshotFlow.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = "Redacted Diagnostics & Latency Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Events: ${snapshot.totalEvents} • Failures: ${snapshot.failureCount} • Mean: ${snapshot.meanLatencyMs}ms • p95: ${snapshot.p95LatencyMs}ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(
                onClick = {
                    AyraDiagnosticsEngine.record(
                        subsystem = "Diagnostics",
                        operation = "manual_probe",
                        durationMs = 1L,
                        success = true,
                        detail = "Diagnostics ring buffer check"
                    )
                },
                modifier = Modifier.size(48.dp).testTag("diagnostics_probe_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Record diagnostics probe", tint = MaterialTheme.colorScheme.primary)
            }
        }

        OutlinedButton(
            onClick = {
                val redactedJson = AyraDiagnosticsEngine.exportRedactedJson()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Ayra Redacted Diagnostics", redactedJson))
                Toast.makeText(context, "Copied secret-redacted diagnostics JSON", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("diagnostics_copy_json_button")
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Copy Redacted Diagnostics JSON", fontSize = 12.sp)
        }

        if (snapshot.recentEvents.isEmpty()) {
            Text(
                text = "No telemetry events recorded yet in this session.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        } else {
            snapshot.recentEvents.take(8).forEach { ev ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "[${ev.subsystem}] ${ev.operation} • ${ev.durationMs}ms • ${if (ev.success) "OK" else "FAIL"}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (ev.detail.isNotBlank()) {
                        Text(
                            text = "${ev.correlationId} • ${ev.detail}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }
        }
    }
}
