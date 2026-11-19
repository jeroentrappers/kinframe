package app.kinframe.family.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.social.Block
import app.kinframe.core.social.Report
import app.kinframe.core.social.ReportStatus
import app.kinframe.core.social.ReportTargetKind
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.social.ChoiceRow
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.personName
import app.kinframe.family.ui.social.reportReasonLabel
import app.kinframe.family.util.Format

/** Settings → Blocked people: unblock. */
@Composable
fun BlockedPeopleScreen(s: Load<List<Block>>, vm: BlocksViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.chat_blocked_title), onBack = onBack)
        when (s) {
            Load.Loading -> SkeletonList(3, 56.dp)
            is Load.Error -> ErrorPanel(s.message, { vm.load() })
            is Load.Ready -> PageColumn(spacing = 10.dp) {
                Text(stringResource(R.string.chat_blocked_lede), style = Kin.type.body, color = c.inkSoft)
                if (s.value.isEmpty()) Note(stringResource(R.string.chat_blocked_none))
                else Surface(Modifier.fillMaxWidth(), padding = 6.dp) {
                    s.value.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PersonAvatar(b.person)
                            Column(Modifier.weight(1f)) {
                                Text(personName(b.person), style = Kin.type.bodyStrong, color = c.ink)
                                b.createdAt?.let { Text(stringResource(R.string.chat_blocked_since, Format.date(it)), style = Kin.type.caption, color = c.inkSoft) }
                            }
                            TextButton({ vm.unblock(b) }) { Text(stringResource(R.string.chat_unblock), color = c.accent) }
                        }
                    }
                }
            }
        }
    }
}

/** A family's reports (owner and admins) or the operator's queue: remove or dismiss. */
@Composable
fun ReportsScreen(s: ReportsState, vm: ReportsViewModel, operator: Boolean, onBack: () -> Unit, onOpenPost: (String) -> Unit, onOpenChat: (String) -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(if (operator) R.string.chat_reports_operator_title else R.string.chat_reports_title), onBack = onBack)
        PageColumn(spacing = 10.dp) {
            Text(stringResource(if (operator) R.string.chat_reports_operator_lede else R.string.chat_reports_lede), style = Kin.type.body, color = c.inkSoft)
            ChoiceRow(listOf("open" to stringResource(R.string.chat_reports_open), "all" to stringResource(R.string.chat_reports_all)), if (s.status == "open") "open" else "all") { vm.setStatus(it) }
            when (val l = s.reports) {
                Load.Loading -> SkeletonList(2, 120.dp)
                is Load.Error -> ErrorPanel(l.message, { vm.load() })
                is Load.Ready -> if (l.value.isEmpty()) Note(stringResource(R.string.chat_reports_none)) else l.value.forEach { r ->
                    ReportCard(r, busy = r.id in s.busy, onRemove = { vm.resolve(r, "remove") }, onDismiss = { vm.resolve(r, "dismiss") }, onOpen = {
                        r.postId?.let(onOpenPost) ?: r.conversationId?.let(onOpenChat)
                    })
                }
            }
        }
    }
}

@Composable
private fun ReportCard(r: Report, busy: Boolean, onRemove: () -> Unit, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth(), onClick = onOpen.takeIf { r.postId != null || r.conversationId != null }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(when (r.targetKind) {
                ReportTargetKind.COMMENT -> R.string.chat_report_kind_comment
                ReportTargetKind.MESSAGE -> R.string.chat_report_kind_message
                else -> R.string.chat_report_kind_post
            }), c.paperDeep, c.inkSoft)
            Pill(stringResource(reportReasonLabel(r.reason)), c.warnSoft, c.warn)
            Text(Format.relative(r.createdAt), style = Kin.type.caption, color = c.inkFaint, modifier = Modifier.weight(1f))
        }
        r.author?.let { Text(stringResource(R.string.chat_report_by, personName(it)), style = Kin.type.label, color = c.ink) }
        r.excerpt?.let { Text("“$it”", style = Kin.type.body, color = c.ink, fontStyle = FontStyle.Italic) }
        r.note?.let { Text(stringResource(R.string.chat_report_note_shown, it), style = Kin.type.caption, color = c.inkSoft) }
        when (r.status) {
            ReportStatus.OPEN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.chat_report_remove), onRemove, enabled = !busy)
                QuietButton(stringResource(R.string.chat_report_dismiss), onDismiss, enabled = !busy)
            }
            ReportStatus.REMOVED -> Text(stringResource(R.string.chat_report_status_removed), style = Kin.type.caption, color = c.inkSoft)
            else -> Text(stringResource(R.string.chat_report_status_dismissed), style = Kin.type.caption, color = c.inkSoft)
        }
    }
}
