package app.kinframe.frame.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinHeading
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeM
import app.kinframe.frame.R
import app.kinframe.frame.ui.Format
import app.kinframe.frame.update.UpdateManager
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// ---------------------------------------------------------------------------------------------- time zone

@Composable
internal fun TimeZoneStep(c: SetupController) {
    val suggestions = remember { c.suggestedTimezones() }
    var selected by remember { mutableStateOf(c.currentTimezone() ?: suggestions.first()) }
    TimeZonePicker(
        suggestions = suggestions,
        selected = selected,
        nowMs = System.currentTimeMillis(),
        onSelect = { selected = it },
        onBack = { c.back() },
        onContinue = { c.chooseTimezone(selected); c.goTo(SetupStep.Server) },
    )
}

/** Stateless picker (also used by the screenshot tests): suggestions first, then every zone by region, no keyboard. */
@Composable
fun TimeZonePicker(
    suggestions: List<String>,
    selected: String,
    nowMs: Long,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val at = Instant.ofEpochMilli(nowMs)
    var region by remember { mutableStateOf(selected.substringBefore('/').takeIf { it in TimeZones.regions } ?: "Europe") }
    val zones = remember(region) { TimeZones.zonesIn(region, at) }
    StepScaffold(index = 2, onBack = onBack, primary = { KinButton(stringResource(R.string.action_continue), onContinue) }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val left = @Composable { mod: Modifier ->
                Column(mod) {
                    KinHeading(stringResource(R.string.setup_tz_title), stringResource(R.string.setup_tz_body))
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.setup_tz_suggested), style = Kin.type.label, color = Kin.colors.inkSoft)
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        suggestions.forEach { id ->
                            val z = TimeZones.zone(id, at)
                            KinCard(Modifier.fillMaxWidth(), selected = id == selected, padding = 18.dp, onClick = { onSelect(id); region = z.region }) {
                                ZoneRow(z, nowMs, locale, id == selected, big = true)
                            }
                        }
                    }
                }
            }
            val right = @Composable { mod: Modifier ->
                Column(mod) {
                    Text(stringResource(R.string.setup_tz_all), style = Kin.type.label, color = Kin.colors.inkSoft)
                    Spacer(Modifier.height(10.dp))
                    ChoiceChips(
                        TimeZones.regions.map { it to regionName(it) }, region, { region = it },
                        Modifier.horizontalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(12.dp))
                    val listState = rememberLazyListState()
                    LaunchedEffect(region) {
                        val i = zones.indexOfFirst { it.id == selected }
                        if (i > 2) listState.scrollToItem(i - 2)
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().clip(KinShapeM).background(Kin.colors.surface),
                        state = listState,
                    ) {
                        items(zones, key = { it.id }) { z ->
                            Box(
                                Modifier.fillMaxWidth().clickable { onSelect(z.id) }
                                    .background(if (z.id == selected) Kin.colors.accentSoft.copy(alpha = 0.6f) else Kin.colors.surface)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            ) { ZoneRow(z, nowMs, locale, z.id == selected, big = false) }
                        }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    left(Modifier.weight(1f))
                    right(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    left(Modifier.fillMaxWidth())
                    right(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(z: TimeZones.Zone, nowMs: Long, locale: Locale, selected: Boolean, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (big) KinIcon(KinIcons.Clock, null, tint = if (selected) Kin.colors.accent else Kin.colors.inkSoft)
        Column(Modifier.weight(1f).padding(start = if (big) 14.dp else 0.dp)) {
            Text(z.city, style = if (big) Kin.type.bodyStrong else Kin.type.body, maxLines = 1)
            if (big) Text(regionName(z.region), style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
        Text(
            Format.clock(nowMs, ZoneId.of(z.id), true, locale) + "  ·  " + TimeZones.offsetLabel(z.offsetSeconds),
            style = Kin.type.caption, color = Kin.colors.inkSoft,
        )
        if (selected) KinIcon(KinIcons.Check, null, tint = Kin.colors.accent, size = 22.dp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun regionName(region: String): String = stringResource(
    when (region) {
        "Europe" -> R.string.tz_region_europe
        "America" -> R.string.tz_region_america
        "Asia" -> R.string.tz_region_asia
        "Africa" -> R.string.tz_region_africa
        "Australia" -> R.string.tz_region_australia
        "Pacific" -> R.string.tz_region_pacific
        "Atlantic" -> R.string.tz_region_atlantic
        "Indian" -> R.string.tz_region_indian
        else -> R.string.tz_region_antarctica
    },
)

// ---------------------------------------------------------------------------------------------- update

/** Looks for a newer kinframe and installs it before the frame code (product spec 4.8). */
@Composable
internal fun UpdateStep(c: SetupController) {
    val status by c.updateStatus().collectAsState()
    var sawWork by remember { mutableStateOf(false) }
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!c.updateCheckStarted) c.startUpdateCheck()
        delay(25_000)
        if (!sawWork) gaveUp = true
    }
    if (status.phase != UpdateManager.Phase.Idle && status.phase != UpdateManager.Phase.UpToDate) sawWork = true
    val upToDate = sawWork && status.phase == UpdateManager.Phase.UpToDate
    LaunchedEffect(upToDate) { if (upToDate) { delay(1_500); c.finishUpdateStep() } }
    val phase = when {
        upToDate -> UpdateManager.Phase.UpToDate
        gaveUp && !sawWork -> UpdateManager.Phase.Failed
        !sawWork -> UpdateManager.Phase.Checking
        else -> status.phase
    }
    UpdateStepView(phase, status.versionName, status.progress, onSkip = { c.finishUpdateStep() })
}

@Composable
fun UpdateStepView(phase: UpdateManager.Phase, versionName: String?, progress: Float?, onSkip: () -> Unit) {
    val failed = phase == UpdateManager.Phase.Failed
    val busy = phase == UpdateManager.Phase.Downloading || phase == UpdateManager.Phase.Installing || phase == UpdateManager.Phase.Waiting
    StepScaffold(
        index = 4, onBack = null,
        primary = {
            when {
                failed || phase == UpdateManager.Phase.UpToDate -> KinButton(stringResource(R.string.action_continue), onSkip)
                phase != UpdateManager.Phase.Installing -> KinButton(stringResource(R.string.setup_update_skip), onSkip, style = KinButtonStyle.Quiet)
            }
        },
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(112.dp).clip(CircleShape).background(if (failed) Kin.colors.paperDeep else if (phase == UpdateManager.Phase.UpToDate) Kin.colors.sageSoft else Kin.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    phase == UpdateManager.Phase.Checking -> CircularProgressIndicator(Modifier.size(52.dp), color = Kin.colors.accent, strokeWidth = 3.dp)
                    phase == UpdateManager.Phase.UpToDate -> KinIcon(KinIcons.Check, null, tint = Kin.colors.sage, size = 52.dp)
                    failed -> KinIcon(KinIcons.WifiOff, null, tint = Kin.colors.inkSoft, size = 48.dp)
                    else -> KinIcon(KinIcons.Download, null, tint = Kin.colors.accent, size = 52.dp)
                }
            }
            Spacer(Modifier.height(32.dp))
            val name = versionName.orEmpty()
            val (title, body) = when (phase) {
                UpdateManager.Phase.UpToDate -> stringResource(R.string.setup_update_current) to stringResource(R.string.setup_update_current_body)
                UpdateManager.Phase.Downloading, UpdateManager.Phase.Waiting ->
                    stringResource(R.string.setup_update_downloading, name) to stringResource(R.string.setup_update_downloading_body)
                UpdateManager.Phase.Installing -> stringResource(R.string.setup_update_installing, name) to stringResource(R.string.setup_update_installing_body)
                UpdateManager.Phase.NeedsUser -> stringResource(R.string.setup_update_installing, name) to stringResource(R.string.update_notice_needs_user)
                UpdateManager.Phase.Failed -> stringResource(R.string.setup_update_failed) to stringResource(R.string.setup_update_failed_body)
                else -> stringResource(R.string.setup_update_checking) to stringResource(R.string.setup_update_checking_body)
            }
            KinHeading(title, body, Modifier.widthIn(max = 620.dp), center = true)
            if (busy) {
                Spacer(Modifier.height(32.dp))
                val p = progress
                if (phase == UpdateManager.Phase.Downloading && p != null) {
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().height(10.dp).clip(CircleShape),
                        color = Kin.colors.accent, trackColor = Kin.colors.paperDeep, drawStopIndicator = {},
                    )
                    Text("${(p * 100).toInt()}%", style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().height(10.dp).clip(CircleShape),
                        color = Kin.colors.accent, trackColor = Kin.colors.paperDeep,
                    )
                }
            }
        }
    }
}
