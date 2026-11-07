package com.altitudeplusplus.android.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.altitudeplusplus.android.AltitudeConfig
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.guide.Program
import com.altitudeplusplus.android.ondemand.Team
import com.altitudeplusplus.android.playback.PlayerActivity
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String, val glyph: String) {
    LIVE("Live", "●"), AVALANCHE("Avalanche", "A"), NUGGETS("Nuggets", "N"), SETTINGS("Settings", "⚙")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val current = Tab.entries[tab]

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (current == Tab.LIVE) "Altitude++" else current.label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = index == tab,
                        onClick = { tab = index },
                        icon = { Text(item.glyph, fontWeight = FontWeight.Bold) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (current) {
                Tab.LIVE -> LiveTab(model)
                Tab.AVALANCHE -> TeamTab(model, Team.AVALANCHE)
                Tab.NUGGETS -> TeamTab(model, Team.NUGGETS)
                Tab.SETTINGS -> SettingsTab(model)
            }
        }
    }
}

@Composable
private fun LiveTab(model: AppViewModel) {
    val context = LocalContext.current
    val guide by model.guide.collectAsStateWithLifecycle()
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) {
        model.refreshGuideIfStale()
        // Ask once for location; Altitude+ falls back to IP lookup if refused.
        if (!asked && !model.graph.location.hasPermission()) {
            asked = true
            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    val now = System.currentTimeMillis()
    val programs = (guide as? Load.Loaded)?.value.orEmpty()
    val onNow = programs.firstOrNull { it.isOn(now) }
    val upcoming = programs.filter { it.start > now }.take(8)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LIVE · ${AltitudeConfig.LIVE_CHANNEL_TITLE}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(onNow?.title ?: "Altitude Sports 24/7", style = MaterialTheme.typography.headlineSmall)
                    onNow?.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    onNow?.let { Text(timeRange(it), style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { context.startActivity(PlayerActivity.live(context, onNow?.title)) }) { Text("Watch live") }
                }
            }
        }
        when (val g = guide) {
            Load.Loading -> item { CircularProgressIndicator() }
            is Load.Failed -> item { Text(g.message, color = MaterialTheme.colorScheme.error) }
            is Load.Loaded -> {
                if (upcoming.isNotEmpty()) item { Text("Up next", style = MaterialTheme.typography.titleMedium) }
                items(upcoming, key = { it.id }) { program ->
                    Column {
                        Text(
                            (if (program.isGame) "🏒🏀 " else "") + program.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(timeRange(program), program.subtitle).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun timeRange(p: Program): String {
    val f = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${f.format(Date(p.start))} – ${f.format(Date(p.end))}"
}

@Composable
private fun TeamTab(model: AppViewModel, team: Team) {
    val context = LocalContext.current
    val all by model.rows.collectAsStateWithLifecycle()
    LaunchedEffect(team) { model.loadTeam(team) }

    when (val state = all[team] ?: Load.Loading) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { model.loadTeam(team, force = true) }) { Text("Try again") }
        }
        is Load.Loaded -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(state.value, key = { it.id }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                            if (index >= row.items.size - 3) LaunchedEffect(row.items.size) { model.loadMore(team, row) }
                            Column(
                                Modifier.width(240.dp).clickable {
                                    context.startActivity(PlayerActivity.video(context, item.id, item.title))
                                },
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
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                                        )
                                    }
                                }
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(model: AppViewModel) {
    val session by model.session.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    val hasLocation = remember { model.graph.location.hasPermission() }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(session?.email ?: "Signed in")
        session?.isSubscribed?.let { Text(if (it) "Subscription active" else "No active subscription on this account") }
        OutlinedButton(onClick = model::signOut) { Text("Sign out") }

        Text("Device profile", style = MaterialTheme.typography.titleMedium)
        Text(
            "Altitude+ has no Android phone identity, so this app presents as Fire TV. Switch to Web browser if streams are refused.",
            style = MaterialTheme.typography.bodySmall,
        )
        DeviceProfile.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { model.setProfile(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == profile, onClick = { model.setProfile(option) })
                Text(option.title)
            }
        }

        Text("Location", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasLocation) "Sending your phone's location for the territory check."
            else "Location is off. Altitude+ will use your IP address for the territory check.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
