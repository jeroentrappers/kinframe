package app.kinframe.frame.data

import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MergePatch
import app.kinframe.frame.sync.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The effective frame settings: the server's document from the change feed with any not-yet-sent local edits
 * merged on top (so a change made on the frame shows immediately, even offline).
 */
class SettingsRepository(private val db: FrameDatabase, scope: CoroutineScope) {
    val raw: StateFlow<JsonObject> = combine(
        db.syncState().observe().map { it?.settingsJson },
        db.outbox().observeKind(OutboxEntity.SETTINGS_PATCH),
    ) { server, pending ->
        var doc: JsonObject = server?.let { runCatching { KinJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        pending.forEach { p ->
            runCatching { doc = MergePatch.apply(doc, KinJson.parseToJsonElement(p.payload)).jsonObject }
        }
        doc
    }.stateIn(scope, SharingStarted.Eagerly, JsonObject(emptyMap()))

    val settings: StateFlow<FrameSettings> = raw.map { FrameSettings.parse(it) }
        .stateIn(scope, SharingStarted.Eagerly, FrameSettings.DEFAULT)

    /** Explicit timezone from settings, if the owner set one. */
    fun explicitTimezone(): String? = (raw.value["locale"] as? JsonObject)?.get("timezone")?.toString()?.trim('"')

    suspend fun edit(outbox: Outbox, patch: JsonObject) = outbox.settingsPatch(patch)
}
