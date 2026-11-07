package com.altitudeplusplus.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.altitudeplusplus.android.download.SavedVideo
import com.altitudeplusplus.android.ondemand.VideoItem
import com.altitudeplusplus.android.playback.PlayerActivity

/** Asks for notification permission (Android 13+) the first time something is saved. */
@Composable
private fun rememberSaveAction(model: AppViewModel): (String, String, String?, Int?) -> Unit {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    return { id, title, image, runtime ->
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        model.save(id, title, image, runtime)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(model: AppViewModel, item: VideoItem) {
    val context = LocalContext.current
    val savedList by model.saved.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val saved = savedList.firstOrNull { it.meta.videoId == item.id }
    var menu by remember { mutableStateOf(false) }
    val save = rememberSaveAction(model)

    Box {
        Column(
            Modifier.width(240.dp).combinedClickable(
                onClick = { context.startActivity(PlayerActivity.video(context, item.id, item.title)) },
                onLongClick = { menu = true },
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                )
                item.durationText?.let {
                    Badge(it, Modifier.align(Alignment.BottomEnd))
                }
                val status = when {
                    item.id in preparing -> "Preparing…"
                    saved == null -> null
                    saved.isComplete -> if (saved.needsInternet) "✓ Downloaded" else "✓ Saved"
                    saved.isFailed -> "Save failed"
                    else -> "Saving ${saved.percent.toInt()}%"
                }
                status?.let { Badge(it, Modifier.align(Alignment.TopStart)) }
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (saved == null) {
                DropdownMenuItem(
                    text = { Text("Save / download ahead") },
                    enabled = item.id !in preparing,
                    onClick = { menu = false; save(item.id, item.title, item.imageUrl, item.runtime) },
                )
            } else {
                DropdownMenuItem(text = { Text("Delete download") }, onClick = { menu = false; model.removeSaved(item.id) })
            }
            DropdownMenuItem(
                text = { Text("Check offline playback") },
                onClick = { menu = false; model.checkOffline(item.id, item.title) },
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.padding(6.dp).background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SavedTab(model: AppViewModel) {
    val context = LocalContext.current
    val saved by model.saved.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val save = rememberSaveAction(model)

    if (saved.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(
                "Nothing saved yet. Long-press a replay or clip on the Avalanche or Nuggets tab and choose Save / download ahead.\n\n" +
                    "Clips are saved as MP4 files in Internal storage/A++ and play anywhere, offline.\n\n" +
                    "Replays download inside the app and play from the phone, but need internet to start: " +
                    "Altitude+ approves each playback online and doesn't allow saved licenses.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(saved, key = { it.meta.videoId }) { video ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = video.meta.imageUrl?.substringBefore('?')?.let { "$it?impolicy=resize&w=640" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = video.isComplete) {
                            context.startActivity(PlayerActivity.video(context, video.meta.videoId, video.meta.title))
                        },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.meta.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(video), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        video.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (video.isActive) LinearProgressIndicator(progress = { video.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Row {
                        if (video.isComplete) {
                            TextButton(onClick = { context.startActivity(PlayerActivity.video(context, video.meta.videoId, video.meta.title)) }) { Text("Play") }
                        }
                        if (video.isFailed) {
                            TextButton(onClick = { save(video.meta.videoId, video.meta.title, video.meta.imageUrl, video.meta.runtime) }) { Text("Retry") }
                        }
                        TextButton(onClick = { model.removeSaved(video.meta.videoId) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun statusLine(video: SavedVideo): String {
    val parts = mutableListOf<String>()
    parts += when {
        video.isComplete -> if (video.needsInternet) "Downloaded" else "Saved"
        video.isFailed -> "Save failed"
        video.isActive -> "Saving ${video.percent.toInt()}%"
        else -> "Paused"
    }
    parts += formatBytes(video.bytes)
    parts += video.meta.quality
    if (video.needsInternet) parts += "needs internet to start"
    return parts.joinToString(" · ")
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

/** Explains and opens Android's All files access screen for this app. */
@Composable
fun FileAccessDialog(model: AppViewModel) {
    val show by model.needsFileAccess.collectAsStateWithLifecycle()
    if (!show) return
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = model::dismissFileAccess,
        title = { Text("Allow saving to A++") },
        text = {
            Text(
                "Clips are saved as MP4 files in a folder called A++ at the top of your internal storage. " +
                    "Android needs \"All files access\" for an app to create that folder. Turn it on for Altitude++, " +
                    "come back, and save the clip again.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                model.dismissFileAccess()
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ),
                )
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = model::dismissFileAccess) { Text("Not now") } },
    )
}
