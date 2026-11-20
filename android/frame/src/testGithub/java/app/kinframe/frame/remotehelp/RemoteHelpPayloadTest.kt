package app.kinframe.frame.remotehelp

import app.kinframe.core.api.Change
import app.kinframe.core.api.ChangeType
import app.kinframe.core.api.KinJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteHelpPayloadTest {
    companion object {
        const val ARM_SHA = "1c753fef45d497491081db37552b7a2a42c7932292743bf6936cb1a42b6bceb0"
        const val ARM64_SHA = "d6bed2720310b328b861f943d7b4016c5358a9bad60575eb51e8143b79068484"

        /** The change payload of docs/spec/remote-help.md §5.2. */
        fun json(
            sessionId: String = "rh_01",
            endsAt: String = "2026-11-20T18:00:00Z",
            authKey: String? = "0a1b2c3d4e5f",
            armSha: String = ARM_SHA,
            hostname: String = "kinframe-36339301",
        ) = """
            {"session_id":"$sessionId","state":"active","ends_at":"$endsAt",
             "login_server":"https://headscale.appmire.be",${authKey?.let { "\"auth_key\":\"$it\"," } ?: ""}
             "hostname":"$hostname","adb_key":"QAAAAJ+x/abc= jeroen@laptop",
             "tailscale":{"version":"1.104.7",
               "arm":{"url":"https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm.tgz","sha256":"$armSha"},
               "arm64":{"url":"https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm64.tgz","sha256":"$ARM64_SHA"}}}
        """.trimIndent()

        fun obj(s: String): JsonObject = KinJson.parseToJsonElement(s).jsonObject
    }

    @Test fun parsesTheChangeFromTheFeed() {
        val change = KinJson.decodeFromString(Change.serializer(), """{"seq":9,"type":"remote_help","id":"frm_1","remote_help":${json()}}""")
        assertEquals(ChangeType.REMOTE_HELP, change.type)
        val p = RemoteHelpPayload.parse(change.remoteHelp)!!
        assertEquals("rh_01", p.sessionId)
        assertEquals("kinframe-36339301", p.hostname)
        assertEquals("0a1b2c3d4e5f", p.authKey)
        assertEquals(1_795_197_600_000L, p.endsAtMs)
        assertEquals(ARM_SHA, p.tailscale.forAbi(TailscaleAbi.ARM)!!.sha256)
        assertEquals("https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm64.tgz", p.tailscale.forAbi(TailscaleAbi.ARM64)!!.url)
        assertNull(p.problem())
        // "remote_help": null is "no session".
        val end = KinJson.decodeFromString(Change.serializer(), """{"seq":10,"type":"remote_help","id":"frm_1","remote_help":null}""")
        assertNull(RemoteHelpPayload.parse(end.remoteHelp))
    }

    @Test fun withoutTheAuthKeyItIsStillUsable() {
        val p = RemoteHelpPayload.parse(obj(json(authKey = null)))!!
        assertNull(p.authKey)
        assertNull(p.problem())
    }

    @Test fun rejectsWhatAShellCouldMisread() {
        fun problem(s: String) = RemoteHelpPayload.parse(obj(s))?.problem()
        assertNotNull(problem(json(hostname = "kinframe-x; reboot")))
        assertNotNull(problem(json(hostname = "Kinframe-UPPER")))
        assertNotNull(problem(json(sessionId = "a'b")))
        assertNotNull(problem(json(authKey = "k\$(id)")))
        assertNotNull(problem(json(armSha = "abc")))
        assertNotNull(problem(json(endsAt = "tomorrow")))
        assertNotNull(problem(json().replace("\"state\":\"active\"", "\"state\":\"ended\"")))
        assertNotNull(problem(json().replace("https://headscale.appmire.be", "https://h'x")))
        assertNull(RemoteHelpPayload.parse(obj("""{"session_id":"x"}"""))) // unreadable
    }

    @Test fun picksTheArchitectureOfTheFramesUserSpace() {
        assertEquals(TailscaleAbi.ARM, TailscaleAbi.pick(listOf("armeabi-v7a", "armeabi")))
        assertEquals(TailscaleAbi.ARM64, TailscaleAbi.pick(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(TailscaleAbi.ARM, TailscaleAbi.pick(listOf("x86", "armeabi-v7a")))
        assertNull(TailscaleAbi.pick(listOf("x86_64")))
    }
}
