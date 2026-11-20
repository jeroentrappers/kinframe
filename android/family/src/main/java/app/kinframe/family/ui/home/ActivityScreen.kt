package app.kinframe.family.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Frame
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.social.freshnessLabel
import app.kinframe.family.ui.social.personName
import app.kinframe.family.util.Format

/** Home › activity: frames asking for photos, then posts with new comments or mentions of you. */
@Composable
fun ActivityScreen(s: HomeState, onBack: () -> Unit, openPost: (TimelinePost) -> Unit, openFrame: (Frame) -> Unit) {
    val c = Kin.colors
    val posts = HomeRow.activity((s.posts as? Load.Ready)?.value.orEmpty())
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.activity_title), onBack = onBack)
        PageColumn(spacing = 10.dp) {
            if (posts.isEmpty() && s.openRequests.isEmpty()) Note(stringResource(R.string.activity_empty))
            s.openRequests.forEach { f ->
                Surface(Modifier.fillMaxWidth(), onClick = { openFrame(f) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(KinIcons.HeartFilled, null, tint = c.sun)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.engagement_frame_would_like, f.name), style = Kin.type.bodyStrong, color = c.ink)
                            freshnessLabel(f.freshness)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                        }
                    }
                }
            }
            posts.forEach { p ->
                Surface(Modifier.fillMaxWidth(), onClick = { openPost(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Thumb(p.items.firstOrNull()?.thumbUrl, null, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(personName(p.poster) + " · " + Format.relative(p.createdAt), style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (p.thread.unreadMentions > 0) stringResource(R.string.social_mentioned_you)
                                else pluralStringResource(R.plurals.activity_comments, p.thread.unreadCount, p.thread.unreadCount),
                                style = Kin.type.bodyStrong, color = c.ink,
                            )
                        }
                    }
                }
            }
        }
    }
}
