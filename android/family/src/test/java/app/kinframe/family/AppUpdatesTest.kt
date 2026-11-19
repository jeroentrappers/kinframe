package app.kinframe.family

import app.kinframe.core.api.AppVersionPolicy
import app.kinframe.family.update.AppUpdates
import app.kinframe.family.update.DismissStore
import app.kinframe.family.update.UpdatePolicy
import app.kinframe.family.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val store = "https://f-droid.org/packages/app.kinframe.family"

    @Test fun noPolicy() {
        assertEquals(UpdateState.None, UpdatePolicy.decide(null, 10, null))
        assertEquals(UpdateState.None, UpdatePolicy.decide(AppVersionPolicy(), 10, null))
    }

    @Test fun belowMinimumIsRequired() {
        assertEquals(UpdateState.Required(store), UpdatePolicy.decide(AppVersionPolicy(12, 15, store), 11, null))
        // A dismissal of the banner does not lift the block.
        assertEquals(UpdateState.Required(null), UpdatePolicy.decide(AppVersionPolicy(12, 15, ""), 11, 15))
    }

    @Test fun betweenMinimumAndRecommendedIsAvailable() {
        assertEquals(UpdateState.Available(15, store), UpdatePolicy.decide(AppVersionPolicy(12, 15, store), 12, null))
        assertEquals(UpdateState.Available(15, null), UpdatePolicy.decide(AppVersionPolicy(null, 15, null), 14, null))
    }

    @Test fun atOrAboveRecommendedIsNone() {
        assertEquals(UpdateState.None, UpdatePolicy.decide(AppVersionPolicy(12, 15, store), 15, null))
        assertEquals(UpdateState.None, UpdatePolicy.decide(AppVersionPolicy(12, 15, store), 99, null))
    }

    @Test fun dismissalIsPerRecommendedBuild() {
        assertEquals(UpdateState.None, UpdatePolicy.decide(AppVersionPolicy(null, 15, null), 14, 15))
        // A newer recommended build shows the banner again.
        assertEquals(UpdateState.Available(16, null), UpdatePolicy.decide(AppVersionPolicy(null, 16, null), 14, 15))
    }

    @Test fun forcedByA426() {
        assertEquals(UpdateState.Required(null), UpdatePolicy.decide(null, 14, null, forced = true))
    }

    @Test fun storeFallbacks() {
        assertEquals(listOf(store, "market://details?id=app.kinframe.family", "https://play.google.com/store/apps/details?id=app.kinframe.family"),
            UpdatePolicy.storeUris(store, "app.kinframe.family"))
        assertEquals("market://details?id=a.b", UpdatePolicy.storeUris(null, "a.b").first())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private class MemoryDismissals : DismissStore { override var dismissedBuild: Int? = null }

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val policy = MutableStateFlow<AppVersionPolicy?>(null)
    private val dismissals = MemoryDismissals()
    private var clock = 0L
    private var reads = 0
    /** What the next discovery read sets the policy to (null: the read fails). */
    private var nextRead: (() -> AppVersionPolicy?)? = { policy.value }
    private var gate: CompletableDeferred<Unit>? = null

    private fun updates(build: Int = 14) = AppUpdates(build, policy, dismissals, refreshDiscovery = {
        reads++
        gate?.await()
        val r = nextRead
        if (r == null) false else { policy.value = r(); true }
    }, scope = scope, now = { clock })

    @Test fun bannerDismissalIsRememberedPerBuild() {
        policy.value = AppVersionPolicy(null, 15, null)
        val u = updates()
        assertEquals(UpdateState.Available(15, null), u.state.value)
        u.dismiss()
        assertEquals(UpdateState.None, u.state.value)
        assertEquals(15, dismissals.dismissedBuild)
        // Remembered across app starts.
        assertEquals(UpdateState.None, updates().state.value)
        policy.value = AppVersionPolicy(null, 16, null)
        scope.runCurrent()
        assertEquals(UpdateState.Available(16, null), u.state.value)
    }

    @Test fun foregroundReadsAreThrottled() {
        val u = updates()
        u.onForeground()
        assertEquals(1, reads)
        clock += 10 * 60 * 1000L
        u.onForeground()
        assertEquals(1, reads)
        clock += 60 * 60 * 1000L
        u.onForeground()
        assertEquals(2, reads)
    }

    @Test fun a426BlocksAndRereadsDiscovery() {
        val u = updates()
        nextRead = { AppVersionPolicy(15, 15, "https://example.org/app") }
        u.onUpdateRequired()
        scope.runCurrent()
        assertEquals(1, reads)
        assertEquals(UpdateState.Required("https://example.org/app"), u.state.value)
    }

    @Test fun a426StaysBlockedWhenDiscoveryCannotBeRead() {
        val u = updates()
        nextRead = null
        u.onUpdateRequired()
        assertEquals(UpdateState.Required(null), u.state.value)
        // Check again, operator lowered the minimum meanwhile: usable again.
        nextRead = { AppVersionPolicy(10, null, null) }
        u.checkAgain()
        assertEquals(UpdateState.None, u.state.value)
        assertFalse(u.checking.value)
    }

    @Test fun aReadStartedBeforeA426DoesNotLiftTheBlock() {
        var calls = 0
        // The read already under way succeeds (with the old document), the one the 426 starts cannot reach the server.
        val pending = CompletableDeferred<Unit>()
        val u = AppUpdates(14, policy, dismissals, refreshDiscovery = {
            val first = calls++ == 0
            pending.await()
            first
        }, scope = scope, now = { clock })
        gate = pending
        u.onForeground()
        assertTrue(u.checking.value)
        u.onUpdateRequired()
        gate!!.complete(Unit)
        scope.runCurrent()
        assertEquals(UpdateState.Required(null), u.state.value)
    }

    @Test fun whileBlockedEveryForegroundChecks() {
        policy.value = AppVersionPolicy(15, null, null)
        val u = updates()
        u.onForeground(); u.onForeground()
        assertEquals(2, reads)
    }
}
