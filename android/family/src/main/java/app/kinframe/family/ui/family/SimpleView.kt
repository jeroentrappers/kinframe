package app.kinframe.family.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.api.User
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.PersonReaction
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.CenteredSpinner
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.home.HomeState
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.personName
import app.kinframe.family.util.Format
import kotlinx.coroutines.launch

/** The simple view's sizes (docs/spec/simple-app.md §6): body type at least 20 sp, buttons at least 64 dp high. */
object SimpleSizes {
    const val BODY_SP = 20
    const val TITLE_SP = 24
    const val BUTTON_DP = 64
}

/** The posts the simple view shows: those with photos, newest first. */
fun simplePosts(posts: List<TimelinePost>): List<TimelinePost> = Families.once(posts).filter { it.items.isNotEmpty() }

/** What the simple view does; wired by the root. */
class SimpleViewActions(
    val onHeart: (TimelinePost) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
    val onNormalView: () -> Unit,
    val onSignOut: () -> Unit,
)

/**
 * The simple view: one photo at a time, large, with the poster's name and time in large type, the caption and a big
 * "Send a heart"; all families together, with the family under the poster; "next" and swiping up and down move through the posts. No tab bar; a small "Settings" opens a sheet
 * with "Normal view" and the account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleViewScreen(familyName: String, s: HomeState, me: User, showFamily: Boolean = false, a: SimpleViewActions) {
    val c = Kin.colors
    val body = Kin.type.body.copy(fontSize = SimpleSizes.BODY_SP.sp, lineHeight = (SimpleSizes.BODY_SP * 1.4).sp)
    var settings by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(familyName, style = Kin.type.headline.copy(fontSize = 28.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() })
            Text(stringResource(R.string.nav_settings), style = body.copy(fontSize = 16.sp), color = c.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { settings = true }.padding(horizontal = 12.dp, vertical = 12.dp))
        }
        when (val p = s.posts) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CenteredSpinner() }
            is Load.Error -> ErrorPanel(p.message, a.onRefresh)
            is Load.Ready -> {
                val posts = simplePosts(p.value)
                if (posts.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.simple_empty), style = body, color = c.inkSoft)
                } else SimplePager(posts, body, showFamily, a)
            }
        }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrimaryButton(stringResource(R.string.simple_normal_view), { settings = false; a.onNormalView() }, Modifier.fillMaxWidth().heightIn(min = SimpleSizes.BUTTON_DP.dp), icon = KinIcons.Home)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(me.displayName, me.avatarUrl, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(me.displayName, style = body, color = c.ink)
                    me.email?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
                }
            }
            SecondaryButton(stringResource(R.string.settings_sign_out), a.onSignOut, Modifier.fillMaxWidth().heightIn(min = SimpleSizes.BUTTON_DP.dp), icon = KinIcons.SignOut)
            Box(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SimplePager(posts: List<TimelinePost>, body: androidx.compose.ui.text.TextStyle, showFamily: Boolean, a: SimpleViewActions) {
    val c = Kin.colors
    val pager = rememberPagerState { posts.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage, posts.size) { if (pager.currentPage >= posts.size - 3) a.onLoadMore() }
    Column(Modifier.fillMaxSize()) {
        VerticalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val p = posts[i]
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val item = p.items.first()
                Thumb(item.largeImageUrl, item.caption ?: p.caption, Modifier.fillMaxWidth().weight(1f), corner = 22.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonAvatar(p.poster, 48.dp)
                    Column {
                        Text(personName(p.poster), style = body.copy(fontSize = SimpleSizes.TITLE_SP.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // With several families: which one it was posted in.
                        Text(listOfNotNull(p.feedName?.takeIf { showFamily }, Format.relative(p.createdAt)).joinToString(" · "), style = body, color = c.inkSoft)
                    }
                }
                (p.caption ?: item.caption)?.takeIf { it.isNotBlank() }?.let { Text(it, style = body, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        }
        val current = posts.getOrNull(pager.currentPage)
        val loved = current?.thread?.reactions?.mine == PersonReaction.LOVE
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).height(SimpleSizes.BUTTON_DP.dp).clip(RoundedCornerShape(32.dp)).background(if (loved) c.love else c.accent)
                    .clickable(enabled = current != null, role = Role.Button) { current?.let(a.onHeart) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(KinIcons.HeartFilled, null, Modifier.size(28.dp), tint = c.onAccent)
                Text(stringResource(if (loved) R.string.simple_heart_sent else R.string.simple_send_heart), style = body.copy(fontSize = 19.sp), color = c.onAccent,
                    modifier = Modifier.padding(start = 10.dp))
            }
            Box(
                Modifier.size(SimpleSizes.BUTTON_DP.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape)
                    .clickable(enabled = pager.currentPage < posts.lastIndex, role = Role.Button, onClickLabel = stringResource(R.string.simple_next)) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(KinIcons.ArrowDown, stringResource(R.string.simple_next), Modifier.size(30.dp), tint = c.ink) }
        }
    }
}
