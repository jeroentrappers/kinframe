package app.kinframe.family.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedPost
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.FeedMute
import app.kinframe.core.social.ThreadSummary
import app.kinframe.family.R
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SwitchRow

/** Family social inside one family's screens (`FeedActions.social`). */
class FeedSocialSlots(
    /** Above the posts: family chat, circles, streak and prompts, reports. */
    val header: @Composable () -> Unit,
    /** Under each post: the reaction row and its comments. */
    val postFooter: @Composable (FeedPost) -> Unit,
    /** In Settings (owner/admins): receipts, new members can post, circles. */
    val settings: @Composable () -> Unit,
    /** In People → You: mute this family. */
    val you: @Composable () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedSocialHeader(
    feed: Feed,
    openReports: Int,
    onChat: (() -> Unit)?,
    onCircles: () -> Unit,
    onEngagement: (() -> Unit)?,
    onReports: (() -> Unit)?,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onChat?.let { SecondaryButton(stringResource(R.string.social_family_chat), it, icon = KinIcons.Message) }
        SecondaryButton(
            if (feed.circleCount > 0) pluralStringResource(R.plurals.social_circles_count, feed.circleCount, feed.circleCount) else stringResource(R.string.social_circles),
            onCircles, icon = KinIcons.People,
        )
        onEngagement?.let {
            val streak = feed.streak
            SecondaryButton(
                if (streak != null && streak.currentWeeks > 0) stringResource(R.string.engagement_streak_short, streak.currentWeeks) else stringResource(R.string.engagement_streak_and_prompts),
                it, icon = KinIcons.Flame,
            )
        }
        if (onReports != null && openReports > 0) SecondaryButton(pluralStringResource(R.plurals.chat_reports_count, openReports, openReports), onReports, icon = KinIcons.Warning)
    }
    feed.streak?.let { s ->
        Text(
            pluralStringResource(R.plurals.engagement_streak_week_progress, s.goal, s.weekPhotos, s.goal),
            style = Kin.type.caption, color = Kin.colors.inkSoft,
        )
    }
}

@Composable
fun FeedPostFooter(t: ThreadSummary, me: String?, onReact: (String?) -> Unit, onOpen: () -> Unit, onWho: () -> Unit) {
    val c = Kin.colors
    ReactionRow(t.reactions, me, onReact, onWho)
    t.latestComments.forEach { CommentLine(it) }
    Text(
        when {
            t.commentCount > 0 -> pluralStringResource(R.plurals.social_view_all_comments, t.commentCount, t.commentCount)
            t.closed -> stringResource(R.string.social_comments_closed)
            else -> stringResource(R.string.social_add_comment)
        },
        style = Kin.type.label, color = c.accent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 6.dp),
    )
}

/** The family settings family social adds (README §5.18). */
@Composable
fun FeedSocialSettings(feed: Feed, busy: Boolean, setFlag: (String, Boolean) -> Unit, onReports: (() -> Unit)?, onEngagement: (() -> Unit)?) {
    SwitchRow(stringResource(R.string.social_setting_see_receipts), feed.membersSeeReceipts, { setFlag("members_see_receipts", it) },
        hint = stringResource(R.string.social_setting_see_receipts_hint), enabled = !busy)
    SwitchRow(stringResource(R.string.social_setting_new_can_post), feed.newMembersCanPost, { setFlag("new_members_can_post", it) },
        hint = stringResource(R.string.social_setting_new_can_post_hint), enabled = !busy)
    SwitchRow(stringResource(R.string.social_setting_circles), feed.membersCanCreateCircles, { setFlag("members_can_create_circles", it) },
        hint = stringResource(R.string.social_setting_circles_hint), enabled = !busy)
    onEngagement?.let { SecondaryButton(stringResource(R.string.engagement_streak_and_prompts), it, icon = KinIcons.Flame) }
    onReports?.let { SecondaryButton(stringResource(R.string.chat_reports_title), it, icon = KinIcons.Warning) }
}

/** People → You: mute this family's posts, chat or both. */
@Composable
fun FeedMuteSection(feed: Feed, chats: Boolean, onMute: (String) -> Unit) {
    SectionTitle(stringResource(R.string.social_family_mute))
    val options = buildList {
        add(FeedMute.NONE to stringResource(R.string.social_mute_none))
        add(FeedMute.POSTS to stringResource(R.string.social_mute_posts))
        if (chats) add(FeedMute.CHAT to stringResource(R.string.social_mute_chat))
        add(FeedMute.ALL to stringResource(R.string.social_mute_all))
    }
    ChoiceRow(options, feed.mute, onMute)
    Text(stringResource(R.string.social_family_mute_hint), style = Kin.type.caption, color = Kin.colors.inkSoft)
}
