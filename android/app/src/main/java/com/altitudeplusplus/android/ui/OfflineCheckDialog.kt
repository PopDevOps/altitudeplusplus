package com.altitudeplusplus.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altitudeplusplus.android.playback.OfflineReport

/** Shows what Altitude+ allows offline for one stream. */
@Composable
fun OfflineCheckDialog(model: AppViewModel) {
    val state by model.offline.collectAsStateWithLifecycle()
    val current = state ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val summary = (current as? Load.Loaded)?.value?.let(::summaryText)

    AlertDialog(
        onDismissRequest = model::dismissOffline,
        title = { Text("Offline playback check") },
        text = {
            Column(scrollModifier(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    Load.Loading -> CircularProgressIndicator()
                    is Load.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                    is Load.Loaded -> Report(current.value)
                }
            }
        },
        confirmButton = { TextButton(onClick = model::dismissOffline) { Text("Done") } },
        dismissButton = {
            if (summary != null) TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Offline check", summary))) } }) { Text("Copy") }
        },
    )
}

@Composable
private fun scrollModifier() = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())

@Composable
private fun Report(report: OfflineReport) {
    Text(report.title, fontWeight = FontWeight.Bold)
    when (report) {
        is OfflineReport.Unencrypted -> Text(
            "Unencrypted clip. Saving puts a normal MP4 in Internal storage/A++ that plays anywhere, offline.",
        )
        is OfflineReport.Protected -> {
            Text(verdict(report.serverAnswer), style = MaterialTheme.typography.titleMedium)
            Text(
                "Asked Altitude+'s license server for a saved license, as a true offline save would.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(report.serverAnswer, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun verdict(answer: String) = when {
    answer.startsWith("GRANTED") -> "✅ Saved licenses allowed — full offline playback possible"
    answer.startsWith("REFUSED") -> "⬇️ Download-ahead only: plays from the phone, but needs internet to start"
    else -> "❔ Couldn't get an answer from the license server"
}

private fun summaryText(report: OfflineReport): String = when (report) {
    is OfflineReport.Unencrypted -> "${report.title}: unencrypted clip"
    is OfflineReport.Protected -> "${report.title}\n${verdict(report.serverAnswer)}\n${report.serverAnswer}"
}
