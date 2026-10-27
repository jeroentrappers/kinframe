package app.kinframe.frame.plugins.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JavaScript budget's clock: CPU time of the JavaScript's thread, requests left out (docs/plugins.md §13). */
class JsClockTest {
    private class FakeCpu : ThreadCpu {
        var thread = 1L
        val used = HashMap<Long, Long>()
        var readable = true
        fun run(ms: Long, on: Long = thread) { used[on] = (used[on] ?: 0L) + ms * 1_000_000 }
        override fun current() = thread
        override fun nanos(thread: Long) = if (readable) used[thread] ?: 0L else -1L
    }

    private fun ms(ns: Long) = ns / 1_000_000

    @Test fun countsCpuTimeNotWaitingForACpu() {
        val cpu = FakeCpu()
        val c = JsClock(cpu).apply { reset(); onJsThread() }
        cpu.run(8)
        Thread.sleep(30) // runnable but not running: the slideshow has the CPU
        assertEquals(8, ms(c.jsNs()))
        assertTrue(ms(c.wallNs()) >= 30)
    }

    @Test fun aRequestAndWhatRunsOnTheThreadMeanwhileDontCount() {
        val cpu = FakeCpu()
        val c = JsClock(cpu).apply { reset(); onJsThread() }
        cpu.run(5)
        c.waitBegins()
        cpu.run(40) // another instance's call on the shared plugin thread
        c.resumed()
        cpu.run(3)
        assertEquals(8, ms(c.jsNs()))
    }

    @Test fun followsTheJavaScriptToAnotherThread() {
        val cpu = FakeCpu()
        val c = JsClock(cpu).apply { reset(); onJsThread() }
        cpu.run(4)
        c.waitBegins()
        cpu.thread = 2 // a WebSocket handler resumes on another pool thread
        c.resumed()
        cpu.run(6, on = 2)
        cpu.run(100, on = 1)
        assertEquals(10, ms(c.jsNs()))
    }

    @Test fun withoutCpuTimeItFallsBackToTheWallClockMinusRequests() {
        val cpu = FakeCpu().apply { readable = false }
        val c = JsClock(cpu).apply { reset(); onJsThread() }
        c.waitBegins()
        Thread.sleep(40)
        c.resumed()
        assertTrue(ms(c.jsNs()) < 20)
        assertTrue(ms(c.wallNs()) >= 40)
    }
}
