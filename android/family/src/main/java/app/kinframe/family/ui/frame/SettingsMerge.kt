package app.kinframe.family.ui.frame

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * JSON merge patch (RFC 7396) helpers for editing a frame's settings document: diff the edited copy against what the
 * server sent, and rebase after `412 version_conflict` (README §5.6: field-level merge, latest write wins).
 */
object SettingsMerge {
    /** The merge patch turning [from] into [to]. Arrays are replaced whole; removed keys become null. */
    fun diff(from: JsonObject, to: JsonObject): JsonObject = buildJsonObject {
        for ((k, b) in to) {
            val a = from[k]
            if (a is JsonObject && b is JsonObject) {
                val sub = diff(a, b)
                if (sub.isNotEmpty()) put(k, sub)
            } else if (a != b) put(k, b)
        }
        for (k in from.keys) if (k !in to) put(k, JsonNull)
    }

    fun apply(target: JsonObject, patch: JsonObject): JsonObject {
        val out = target.toMutableMap()
        for ((k, v) in patch) {
            when {
                v is JsonNull -> out.remove(k)
                v is JsonObject -> out[k] = apply(out[k] as? JsonObject ?: JsonObject(emptyMap()), v)
                else -> out[k] = v
            }
        }
        return JsonObject(out)
    }

    /** Leaf paths of a patch: ["slideshow.interval_s", "display.sleep_schedules"]. */
    fun paths(patch: JsonObject, prefix: String = ""): List<String> = patch.flatMap { (k, v) ->
        val p = if (prefix.isEmpty()) k else "$prefix.$k"
        if (v is JsonObject && v.isNotEmpty()) paths(v, p) else listOf(p)
    }

    fun overlap(a: JsonObject, b: JsonObject): Boolean {
        val pa = paths(a)
        val pb = paths(b)
        return pa.any { x -> pb.any { y -> x == y || x.startsWith("$y.") || y.startsWith("$x.") } }
    }

    sealed interface Rebase {
        /** The other change touched different fields: send ours again on the new version. */
        data class Retry(val patch: JsonObject) : Rebase
        /** Same fields changed: show ours on top of theirs for review. */
        data class Review(val merged: JsonObject) : Rebase
    }

    fun rebase(base: JsonObject, latest: JsonObject, ours: JsonObject): Rebase {
        val theirs = diff(strip(base), strip(latest))
        return if (!overlap(theirs, ours)) Rebase.Retry(ours) else Rebase.Review(apply(latest, ours))
    }

    /** Sets a value at a dotted path, creating objects on the way; null removes it. */
    fun set(doc: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        val out = doc.toMutableMap()
        if (path.size == 1) {
            if (value == null) out.remove(path[0]) else out[path[0]] = value
        } else {
            out[path[0]] = set(doc[path[0]] as? JsonObject ?: JsonObject(emptyMap()), path.drop(1), value)
        }
        return JsonObject(out)
    }

    private fun strip(o: JsonObject) = JsonObject(o - "version")
}
