package tally.app.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.AppContainer
import tally.app.data.SourceStats
import tally.app.quickentry.QuickEntryTileService
import tally.app.ui.components.ConfirmSheet
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillStyle
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.SwitchRow
import tally.app.ui.components.TallyCard
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.ThinProgress
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.capture.WatchedPackages

/** What capture needs from the phone, how the listener is doing, and the debug switch. */
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
    val tileAdded by container.settings.quickAddTileAdded.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val stats by remember { container.rawEvents.observeSourceStats() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Denied twice means Android won't prompt again; go to settings instead.
        if (!granted) context.startFirst(SetupIntents.appNotifications(context))
        refresh++
    }

    val checks = listOf(
        Check(
            ok = status.listenerEnabled,
            title = "Notification access",
            detail = "Lets Tally read bank and wallet notifications. If the toggle is greyed out, " +
                "do \"Allow restricted settings\" first.",
            action = "Open" to { context.startFirst(SetupIntents.notificationAccess(context)) },
        ),
        Check(
            ok = status.listenerEnabled,
            title = "Allow restricted settings",
            detail = "Android blocks notification access for sideloaded apps. App info, ⋮, Allow restricted settings.",
            action = "App info" to { context.startFirst(SetupIntents.appDetails(context)) },
        ),
        Check(
            ok = status.notificationsAllowed,
            title = "Post notifications",
            detail = "For capture notifications (\"\$4.25 · Tim Hortons\").",
            action = "Allow" to {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    context.startFirst(SetupIntents.appNotifications(context))
                } else {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        ),
        Check(
            ok = status.batteryUnrestricted,
            title = "Battery unrestricted",
            detail = "Otherwise the system may stop the listener and captures are silently missed.",
            action = "Fix" to { context.startFirst(SetupIntents.batteryOptimization(context)) },
        ),
        Check(
            ok = null,
            title = "Never sleeping apps (Samsung)",
            detail = "Can't be checked from here. Settings, Battery, Background usage limits, Never sleeping apps: add Tally.",
            action = null,
        ),
        Check(
            ok = tileAdded,
            title = "Quick add tile",
            detail = "Swipe down, tap Quick add, and log an expense in a few taps, even from the lock screen.",
            action = "Add" to { QuickEntryTileService.requestAdd(context) },
        ),
    )
    val checkable = checks.filter { it.ok != null }
    val done = checkable.count { it.ok == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TallyTopBar("Setup", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            val colors = Tally.colors
            TallyCard(
                Modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp).fillMaxWidth(),
                color = colors.hero,
                shape = RoundedCornerShape(28.dp),
                padding = PaddingValues(22.dp),
            ) {
                MonoLabel("Capture checklist", color = colors.onHeroMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (done == checkable.size) "All set" else "$done of ${checkable.size} done",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.onHero,
                )
                Spacer(Modifier.height(12.dp))
                ThinProgress(done.toFloat() / checkable.size, colors.heroAccent, colors.heroTrack)
            }

            SectionHeader("Checklist")
            Column(Modifier.padding(horizontal = ScreenPadding).clip(RoundedCornerShape(22.dp))) {
                checks.forEachIndexed { i, check ->
                    CheckRow(check)
                    if (i < checks.lastIndex) RowDivider(start = 64.dp)
                }
            }

            SectionHeader("Capture")
            TallyCard(Modifier.padding(horizontal = ScreenPadding).fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                SwitchRow("Log all packages", logAll, { scope.launch { container.settings.setLogAllPackages(it) } })
                Text(
                    "Debug: record notifications from every app, to find a bank's real package name. Turn it off after a day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tally.colors.muted,
                )
                val otherCount = stats.filter { it.source !in WatchedPackages.ALL }.sumOf { it.eventCount }
                if (otherCount > 0) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$otherCount from other apps",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        PillButton("Delete", { confirmDelete = true }, style = PillStyle.Danger, icon = TallyIcons.Trash, height = 38.dp)
                    }
                    if (confirmDelete) {
                        ConfirmSheet(
                            title = "Delete $otherCount notifications?",
                            body = "Removes everything recorded from apps other than RBC and the wallets. Bank and wallet notifications are kept. This can't be undone.",
                            confirm = "Delete",
                            onConfirm = { scope.launch { container.rawEvents.deleteUnwatched() } },
                            onDismiss = { confirmDelete = false },
                        )
                    }
                }
            }

            SectionHeader("Listener health")
            HealthList(stats)
        }
    }
}

private class Check(val ok: Boolean?, val title: String, val detail: String, val action: Pair<String, () -> Unit>?)

@Composable
private fun CheckRow(check: Check) {
    val (icon, tint, background) = when (check.ok) {
        true -> Triple(TallyIcons.Check, MaterialTheme.colorScheme.onSecondaryContainer, Tally.colors.positiveSoft)
        false -> Triple(TallyIcons.Bell, MaterialTheme.colorScheme.onTertiaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
        null -> Triple(TallyIcons.Sliders, Tally.colors.muted, Tally.colors.chip)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = if (check.ok == true) "Done" else if (check.ok == false) "Needs attention" else "Manual step", Modifier.size(18.dp), tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(check.title, style = MaterialTheme.typography.titleSmall)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted)
        }
        val action = check.action
        if (action != null && check.ok != true) {
            Spacer(Modifier.width(10.dp))
            PillButton(action.first, action.second, style = PillStyle.Primary, height = 36.dp)
        }
    }
}

@Composable
private fun HealthList(stats: List<SourceStats>) {
    val bySource = stats.associateBy { it.source }
    val now = System.currentTimeMillis()
    val watched = WatchedPackages.ALL.map { pkg -> Triple(WatchedPackages.displayName(pkg), pkg, bySource[pkg]) }
    val others = stats.filter { it.source !in WatchedPackages.ALL }.sortedByDescending { it.lastReceivedAt }
    Column(Modifier.padding(horizontal = ScreenPadding).clip(RoundedCornerShape(22.dp))) {
        watched.forEachIndexed { i, (name, pkg, s) ->
            HealthRow(name, pkg, s, now)
            if (i < watched.lastIndex) RowDivider(start = 40.dp)
        }
    }
    if (others.isNotEmpty()) {
        SectionHeader("Other packages seen")
        Column(Modifier.padding(horizontal = ScreenPadding).clip(RoundedCornerShape(22.dp))) {
            others.forEachIndexed { i, s ->
                HealthRow(s.source, null, s, now)
                if (i < others.lastIndex) RowDivider(start = 40.dp)
            }
        }
    }
}

/** Green dot: heard from in the last week. */
@Composable
private fun HealthRow(name: String, pkg: String?, stats: SourceStats?, now: Long) {
    val recent = stats != null && now - stats.lastReceivedAt < DateUtils.WEEK_IN_MILLIS
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (recent) Tally.colors.positive else if (stats == null) Tally.colors.faint else Tally.colors.warn))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pkg != null) {
                Text(pkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono), color = Tally.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MonoLabel(stats?.let { "${it.eventCount} · ${DateUtils.getRelativeTimeSpanString(it.lastReceivedAt)}" } ?: "never", color = if (stats == null) Tally.colors.faint else Tally.colors.muted)
    }
}
