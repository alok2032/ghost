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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.security.AyraDiagnosticsEngine
import com.ayra.ai.app.security.ConnectionHealthStatus
import com.ayra.ai.app.security.DiagnosticCheckState
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.security.NetworkDiagnosticsReport
import com.ayra.ai.app.security.NetworkFailureCategory
import com.ayra.ai.app.security.NetworkTransportType
import com.ayra.ai.app.security.SubsystemDiagnosticResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AyraDiagnosticsSection(
    context: Context,
    refreshOnMount: Boolean = true,
    testSuiteRunner: suspend (Context) -> NetworkDiagnosticsReport = { ctx ->
        NetworkConnectivityManager.runAllConnectionTests(ctx)
    }
) {
    val snapshot by AyraDiagnosticsEngine.snapshotFlow.collectAsStateWithLifecycle()
    val connectivityState by NetworkConnectivityManager.stateFlow.collectAsStateWithLifecycle()
    val diagnosticsReport by NetworkConnectivityManager.diagnosticsFlow.collectAsStateWithLifecycle()
    val recoveryCount by NetworkConnectivityManager.recoveryCountFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshOnMount) {
        if (refreshOnMount) {
            NetworkConnectivityManager.refreshFromContext(context)
        }
    }

    val statusTint = when (connectivityState.status) {
        ConnectionHealthStatus.CONNECTED_VALIDATED -> MaterialTheme.colorScheme.primary
        ConnectionHealthStatus.CONNECTED_UNVALIDATED -> MaterialTheme.colorScheme.tertiary
        ConnectionHealthStatus.CAPTIVE_PORTAL,
        ConnectionHealthStatus.OFFLINE -> MaterialTheme.colorScheme.error
    }

    val transportIcon = when {
        connectivityState.status == ConnectionHealthStatus.OFFLINE -> Icons.Default.WifiOff
        connectivityState.status == ConnectionHealthStatus.CAPTIVE_PORTAL -> Icons.Default.Warning
        connectivityState.transportType == NetworkTransportType.WIFI -> Icons.Default.Wifi
        connectivityState.transportType == NetworkTransportType.CELLULAR -> Icons.Default.SignalCellularAlt
        connectivityState.transportType == NetworkTransportType.VPN -> Icons.Default.VpnLock
        connectivityState.transportType == NetworkTransportType.ETHERNET -> Icons.Default.SettingsEthernet
        else -> Icons.Default.NetworkCheck
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, statusTint.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("network_diagnostics_dashboard"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header + Refresh Probe Button
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
                Icon(transportIcon, contentDescription = null, tint = statusTint)
                Column {
                    Text(
                        text = "Network & Connection Diagnostics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTint
                    )
                    Text(
                        text = connectivityState.statusSummary,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("network_status_summary_text")
                    )
                }
            }
            IconButton(
                onClick = {
                    if (!diagnosticsReport.isRunning) {
                        scope.launch { testSuiteRunner(context) }
                    }
                },
                enabled = !diagnosticsReport.isRunning,
                modifier = Modifier.size(48.dp).testTag("diagnostics_probe_button")
            ) {
                if (diagnosticsReport.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Run live network diagnostics",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Metadata line: last success / last failure / recovery triggers
        val lastSuccessText = (diagnosticsReport.lastSuccessMs ?: connectivityState.lastSuccessfulConnectionMs)?.let { ts ->
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
        } ?: "Never verified"
        val lastFailText = connectivityState.lastFailureCategory
            .takeIf { it != NetworkFailureCategory.NONE }
            ?.label
            ?: "None"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Transport: ${connectivityState.transportType.label} • Validated: ${if (connectivityState.isValidated) "Yes" else "No"} • Recoveries: $recoveryCount",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Last Success: $lastSuccessText • Last Issue: $lastFailText",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Primary "Test All Connections" button
        Button(
            onClick = {
                if (!diagnosticsReport.isRunning) {
                    scope.launch { testSuiteRunner(context) }
                }
            },
            enabled = !diagnosticsReport.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("test_all_connections_button")
        ) {
            if (diagnosticsReport.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Testing DNS, TLS, Backend, Firebase & AI...", fontSize = 12.sp)
            } else {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Test All Connections", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Subsystem results list (honest live status only — zero fake greens)
        val subsystemResults = listOfNotNull(
            diagnosticsReport.internetCheck,
            diagnosticsReport.dnsCheck,
            diagnosticsReport.httpsCheck,
            diagnosticsReport.backendCheck,
            diagnosticsReport.firebaseCheck,
            diagnosticsReport.aiProvidersCheck
        )

        if (subsystemResults.isEmpty()) {
            Text(
                text = "Tap \"Test All Connections\" to run real DNS, TLS handshake, Backend /health, Firebase, and active AI provider checks.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("diagnostics_untested_hint")
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("diagnostics_results_list")
            ) {
                subsystemResults.forEach { result ->
                    SubsystemResultRow(result = result)
                }
                if (diagnosticsReport.activeProviderResults.isNotEmpty()) {
                    Text(
                        text = "Per-Provider Connection Results",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    diagnosticsReport.activeProviderResults.forEach { providerResult ->
                        SubsystemResultRow(result = providerResult)
                    }
                }
            }
        }

        // Telemetry Header + Copy JSON
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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

@Composable
private fun SubsystemResultRow(result: SubsystemDiagnosticResult) {
    val badgeColor: Color = when (result.state) {
        DiagnosticCheckState.PASS -> MaterialTheme.colorScheme.primary
        DiagnosticCheckState.DEGRADED -> MaterialTheme.colorScheme.tertiary
        DiagnosticCheckState.FAIL -> MaterialTheme.colorScheme.error
        DiagnosticCheckState.UNCONFIGURED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (result.state) {
        DiagnosticCheckState.PASS -> Icons.Default.CheckCircle
        DiagnosticCheckState.DEGRADED -> Icons.Default.Warning
        DiagnosticCheckState.FAIL -> Icons.Default.Error
        DiagnosticCheckState.UNCONFIGURED -> Icons.Default.NetworkCheck
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.8.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("diagnostic_row_${result.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = result.state.label,
            tint = badgeColor,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildString {
                        append(result.state.label)
                        if (result.latencyMs != null) append(" (${result.latencyMs}ms)")
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }
            Text(
                text = result.detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
