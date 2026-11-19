package app.kinframe.frame.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FramePrompt
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.FrameSocialSettings
import app.kinframe.core.api.PlacementComment
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.ui.SectionCard
import app.kinframe.frame.ui.SwitchRow

/*
 * Family social on the slideshow (docs/spec/family-social.md §8, §9.3). Plain text on the overlay layer: no avatars,
 * no images, nothing decoded; the cards only fade (alpha in the draw phase), so a shown card costs no recomposition
 * per animation frame on the RK3126.
 */

/** Translucent dark like the quick menu's bars: readable on any photo without covering much of it. */
private val CardBackground = Color(0xD91C1714)

/**
 * The prompt's title card, the comment card and "New photos from Ben", stacked at the top centre of the photo. Hidden
 * (not removed: their timers keep running) while a menu, a sheet or another card is up.
 */
@Composable
fun SocialCardsLayer(c: FrameController, hidden: Boolean, modifier: Modifier = Modifier) {
    val cards by c.social.cards.collectAsState()
    val note by c.social.note.collectAsState()
    Column(modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AnimatedVisibility(!hidden && note != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(700))) {
            // Keeps the last names while it fades out.
            val last = remember { arrayOfNulls<NewPhotosNote>(1) }
            note?.let { last[0] = it }
            last[0]?.let { NewPhotosNoteContent(it.names) }
        }
        val prompt = cards?.prompt
        AnimatedVisibility(!hidden && prompt != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(700))) {
            val last = remember { arrayOfNulls<FramePrompt>(1) }
            prompt?.let { last[0] = it }
            last[0]?.let { PromptCardContent(it) }
        }
        val comment = cards?.comment
        AnimatedVisibility(!hidden && comment != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(700))) {
            val last = remember { arrayOfNulls<PlacementComment>(1) }
            comment?.let { last[0] = it }
            last[0]?.let { CommentCardContent(it) }
        }
    }
}

/** "This week in Peeters" above the prompt's title. */
@Composable
internal fun PromptCardContent(prompt: FramePrompt) {
    Column(
        Modifier.widthIn(max = 560.dp).clip(KinShapeL).background(CardBackground).padding(horizontal = 32.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = if (prompt.feedName.isBlank()) stringResource(R.string.social_prompt_this_week)
        else stringResource(R.string.social_prompt_this_week_in, prompt.feedName)
        Text(label, style = Kin.type.label, color = Kin.colors.accentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            prompt.title, style = Kin.type.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The newest comment: the first name, then the text, cut after two lines. Never a conversation. */
@Composable
internal fun CommentCardContent(comment: PlacementComment) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Kin.colors.accentSoft)) { append(comment.authorName.trim()) }
        if (comment.authorName.isNotBlank()) append("  ")
        append(comment.body.trim().replace('\n', ' '))
    }
    Row(
        Modifier.widthIn(max = 560.dp).clip(KinShapeL).background(CardBackground).padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(KinIcons.Message, null, tint = Color.White.copy(alpha = 0.8f), size = 22.dp)
        Text(
            text, style = Kin.type.body, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** "New photos from Ben", "… from Ben and Anna", "… from Ben, Anna and others". */
@Composable
internal fun NewPhotosNoteContent(names: List<String>) {
    val text = when (names.size) {
        0 -> return
        1 -> stringResource(R.string.social_new_photos_from_one, names[0])
        2 -> stringResource(R.string.social_new_photos_from_two, names[0], names[1])
        else -> stringResource(R.string.social_new_photos_from_more, names[0], names[1])
    }
    Row(
        Modifier.widthIn(max = 640.dp).clip(CircleShape).background(CardBackground).padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(KinIcons.Image, null, tint = Color.White.copy(alpha = 0.85f), size = 22.dp)
        Text(text, style = Kin.type.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The line after "Ask for new photos", at the bottom of the screen for a few seconds. */
@Composable
fun AskConfirmationLayer(c: FrameController, modifier: Modifier = Modifier) {
    val confirmation by c.social.confirmation.collectAsState()
    AnimatedVisibility(confirmation != null, modifier, enter = fadeIn(tween(300)), exit = fadeOut(tween(500))) {
        val last = remember { arrayOfNulls<AskConfirmation>(1) }
        confirmation?.let { last[0] = it }
        last[0]?.let { AskConfirmationContent(it) { c.social.dismissConfirmation() } }
    }
}

@Composable
internal fun AskConfirmationContent(confirmation: AskConfirmation, onClick: () -> Unit = {}) {
    Row(
        Modifier.widthIn(max = 720.dp).clip(CircleShape).background(Color(0xF02E2621)).clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (confirmation == AskConfirmation.Asked) R.string.social_ask_sent else R.string.social_ask_offline),
            style = Kin.type.body, color = Color.White,
        )
    }
}

/**
 * The quick menu's "Ask for new photos": a labelled pill in the top bar. After a tap (and until the server allows the
 * next one) it reads "Asked today 💛", or who answered.
 */
@Composable
internal fun AskPhotosAction(state: AskState, onAsk: () -> Unit) {
    val ready = state == AskState.Ready
    val label = when (state) {
        AskState.Ready -> stringResource(R.string.social_ask_photos)
        AskState.Asked -> stringResource(R.string.social_asked_today)
        is AskState.Answered -> stringResource(R.string.social_ask_answered, state.name)
    }
    Row(
        Modifier.height(64.dp).clip(CircleShape)
            .background(if (ready) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
            .clickable(enabled = ready, onClick = onAsk)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ready) KinIcon(KinIcons.Image, null, tint = Color.White, size = 24.dp)
        Text(
            label, style = Kin.type.label, color = if (ready) Color.White else Color.White.copy(alpha = 0.85f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (ready) 10.dp else 0.dp).widthIn(max = 300.dp),
        )
    }
}

/** Frame settings → Family: the four `settings.social` switches (the owner's PIN guards the settings screen). */
@Composable
fun SocialSettingsSection(c: FrameController, @Suppress("UNUSED_PARAMETER") s: FrameSettings, section: (String, Array<out Pair<String, Any?>>) -> Unit) {
    val social: FrameSocialSettings by c.social.settings.collectAsState()
    SectionCard(stringResource(R.string.social_settings_title)) {
        SwitchRow(stringResource(R.string.social_settings_show_comments), social.showComments, subtitle = stringResource(R.string.social_settings_show_comments_hint)) {
            section("social", arrayOf("show_comments" to it))
        }
        SwitchRow(stringResource(R.string.social_settings_ask), social.askForPhotos) { section("social", arrayOf("ask_for_photos" to it)) }
        SwitchRow(stringResource(R.string.social_settings_prompts), social.promptCards) { section("social", arrayOf("prompt_cards" to it)) }
        SwitchRow(stringResource(R.string.social_settings_new_from), social.newPhotosFrom) { section("social", arrayOf("new_photos_from" to it)) }
    }
}
