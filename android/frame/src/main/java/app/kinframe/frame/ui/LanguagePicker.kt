package app.kinframe.frame.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.Locales

/**
 * Every language in its own name and script (so anyone can find theirs whatever the frame shows now), in a grid
 * that fits 36 languages on the frame's screen. Tapping one selects it; the caller switches the app language.
 */
@Composable
fun LanguageGrid(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, columns: Int = 4) {
    val languages = Locales.supported
    // Open with the selected language's row in view (one row of context above it).
    val first = ((languages.indexOfFirst { it.code == selected } / columns) - 1).coerceAtLeast(0) * columns
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = first)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items(languages, key = { it.code }) { lang ->
            LanguageCell(lang, lang.code == selected, { onSelect(lang.code) }, Modifier.fillMaxWidth())
        }
    }
}

/** The same choices without lazy layout, for use inside a scrolling column (frame settings). */
@Composable
fun LanguageChoices(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, columns: Int = 4) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Locales.supported.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { lang -> LanguageCell(lang, lang.code == selected, { onSelect(lang.code) }, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LanguageCell(lang: Locales.Language, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    KinCard(modifier.heightIn(min = 64.dp), selected = isSelected, onClick = onClick, padding = 16.dp) {
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                lang.nativeName,
                // Shape each name with its own language (Han glyphs differ between zh-CN, zh-TW and ja).
                style = Kin.type.bodyStrong.copy(localeList = LocaleList(lang.locale.toLanguageTag())),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) KinIcon(KinIcons.Check, null, Modifier.padding(start = 8.dp), tint = Kin.colors.accent, size = 22.dp)
        }
    }
}
