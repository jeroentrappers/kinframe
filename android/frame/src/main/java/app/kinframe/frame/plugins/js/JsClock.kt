package app.kinframe.frame.plugins.js

import java.io.File

/** CPU time per thread: what the JavaScript budget counts, so a thread waiting for a busy CPU isn't charged. */
interface ThreadCpu {
    /** The calling thread. */
    fun current(): Long

    /** CPU time [thread] has used so far in nanoseconds, or -1 when it can't be read. */
    fun nanos(thread: Long): Long

    /** Linux (the frame): the first field of `/proc/self/task/<tid>/schedstat`, nanoseconds on a CPU. */
    object Proc : ThreadCpu {
        override fun current(): Long = android.os.Process.myTid().toLong()
        override fun nanos(thread: Long): Long =
            runCatching { File("/proc/self/task/$thread/schedstat").readText().substringBefore(' ').trim().toLong() }.getOrDefault(-1L)
    }
}

/**
 * How much of one call was JavaScript. The plugin thread is shared and runs at background priority, so on the
 * Denver a call easily spends tens of milliseconds waiting for a CPU while the slideshow decodes the next photo;
 * wall-clock time would charge the plugin for that. This clock counts the CPU time of the thread the JavaScript runs
 * on ([onJsThread], called by the prelude when a call starts), and pauses from the moment a `ctx.http` request starts
 * ([waitBegins]) until the JavaScript resumes after it ([resumed]), so another instance running on the thread
 * meanwhile isn't charged either. Where CPU time can't be read it falls back to wall-clock time minus the waits.
 */
class JsClock(private val cpu: ThreadCpu) {
    private var startNs = 0L
    private var thread = -1L
    private var cpuOk = true
    private var running = false   // the JavaScript holds the thread (not waiting for a request)
    private var segmentCpu = 0L   // CPU time of [thread] when the current running stretch began
    private var doneCpu = 0L      // CPU time of the finished stretches
    private var waits = 0
    private var waitStartNs = 0L
    private var waitedNs = 0L

    @Synchronized fun reset() {
        startNs = System.nanoTime()
        thread = -1L; cpuOk = true; running = false
        segmentCpu = 0L; doneCpu = 0L
        waits = 0; waitStartNs = 0L; waitedNs = 0L
    }

    /** The JavaScript runs on the calling thread from now on (start of a call, or back after a request). */
    @Synchronized fun onJsThread() {
        if (running && thread == cpu.current()) return
        if (running) close()
        thread = cpu.current()
        segmentCpu = cpu.nanos(thread).also { if (it < 0) cpuOk = false }
        running = true
    }

    /** A `ctx.http` request starts (any thread). */
    @Synchronized fun waitBegins() {
        if (waits++ > 0) return
        waitStartNs = System.nanoTime()
        if (running) { close(); running = false }
    }

    /** The JavaScript continues after a request (on its thread). */
    @Synchronized fun resumed() {
        if (waits == 0) return
        if (--waits > 0) return
        waitedNs += System.nanoTime() - waitStartNs
        onJsThread()
    }

    /** Nanoseconds on the wall clock since [reset]. */
    @Synchronized fun wallNs(): Long = System.nanoTime() - startNs

    /** Nanoseconds of JavaScript since [reset]. */
    @Synchronized fun jsNs(): Long {
        if (cpuOk && thread >= 0) {
            val now = if (running) cpu.nanos(thread) else -1L
            if (!running || now >= 0) return doneCpu + (if (running) now - segmentCpu else 0L)
            cpuOk = false
        }
        val t = System.nanoTime()
        return t - startNs - waitedNs - (if (waits > 0) t - waitStartNs else 0L)
    }

    private fun close() {
        val now = cpu.nanos(thread)
        if (now < 0) cpuOk = false else doneCpu += now - segmentCpu
    }
}
