package tally.app.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.AppContainer
import tally.app.data.SourceStats
import tally.app.ui.theme.OkGreen
import tally.app.ui.theme.WarnAmber
import tally.core.capture.WatchedPackages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped on resume (returning from system settings) and after permission prompts.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val status = remember(refresh) { SetupStatus.read(context) }
    val logAll by container.settings.logAllPackages.collectAsStateWithLifecycle()
    val stats by remember { container.rawEvents.observeSourceStats() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Denied twice means Android won't prompt again; go to settings instead.
        if (!granted) context.startFirst(SetupIntents.appNotifications(context))
        refresh++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Checklist")
            CheckItem(
                ok = status.listenerEnabled,
                title = "Notification access",
                detail = "Lets Tally read bank and wallet notifications. If the toggle is greyed " +
                    "out, first use \"Allow restricted settings\" below.",
                action = "Open" to { context.startFirst(SetupIntents.notificationAccess(context)) },
            )
            CheckItem(
                ok = status.listenerEnabled,
                title = "Allow restricted settings",
                detail = "Android 13+ blocks notification access for sideloaded apps. " +
                    "App info, three-dot menu, Allow restricted settings.",
                action = "App info" to { context.startFirst(SetupIntents.appDetails(context)) },
            )
            CheckItem(
                ok = status.notificationsAllowed,
                title = "Post notifications",
                detail = "For capture notifications (\"\$4.25 Tim Hortons\").",
                action = "Allow" to {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        context.startFirst(SetupIntents.appNotifications(context))
                    } else {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            CheckItem(
                ok = status.batteryUnrestricted,
                title = "Battery unrestricted",
                detail = "Without this, the system may kill the listener and captures are " +
                    "silently missed.",
                action = "Fix" to { context.startFirst(SetupIntents.batteryOptimization(context)) },
            )
            CheckItem(
                ok = null,
                title = "Never sleeping apps (Samsung)",
                detail = "Can't be checked from the app. Settings, Battery, Background usage " +
                    "limits, Never sleeping apps: add Tally.",
                action = null,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Capture")
            ListItem(
                headlineContent = { Text("Log all packages") },
                supportingContent = {
                    Text(
                        "Debug: record notifications from every app. Use for a day to confirm " +
                            "the real bank and wallet package names, then turn it off.",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = logAll,
                        onCheckedChange = { scope.launch { container.settings.setLogAllPackages(it) } },
                    )
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Listener health")
            HealthList(stats)
        }
    }
}

@Composable
private fun HealthList(stats: List<SourceStats>) {
    val bySource = stats.associateBy { it.source }
    WatchedPackages.ALL.forEach { pkg ->
        val s = bySource[pkg]
        ListItem(
            headlineContent = { Text(WatchedPackages.displayName(pkg)) },
            supportingContent = { Text(pkg) },
            trailingContent = { Text(s?.let { describe(it) } ?: "never") },
        )
    }
    val others = stats.filter { it.source !in WatchedPackages.ALL }.sortedByDescending { it.lastReceivedAt }
    if (others.isNotEmpty()) {
        SectionHeader("Other packages seen")
        others.forEach { s ->
            ListItem(
                headlineContent = { Text(s.source) },
                trailingContent = { Text(describe(s)) },
            )
        }
    }
}

private fun describe(s: SourceStats): String =
    "${s.eventCount} · ${DateUtils.getRelativeTimeSpanString(s.lastReceivedAt)}"

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** [ok] null means the state can't be checked from the app. */
@Composable
private fun CheckItem(
    ok: Boolean?,
    title: String,
    detail: String,
    action: Pair<String, () -> Unit>?,
) {
    val (icon: ImageVector, tint, description) = when (ok) {
        true -> Triple(Icons.Default.CheckCircle, OkGreen, "Done")
        false -> Triple(Icons.Default.Warning, WarnAmber, "Needs attention")
        null -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Manual step")
    }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = description, tint = tint) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = if (action != null && ok != true) {
            { TextButton(onClick = action.second) { Text(action.first) } }
        } else {
            null
        },
    )
}
