package app.kinframe.frame

import android.app.Application
import android.content.Context

class FrameApp : Application() {
    lateinit var graph: FrameGraph
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Locales.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        graph = FrameGraph(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { graph.crashGuard.recordCrash() }
            previous?.uncaughtException(t, e)
        }
        graph.controller.start()
    }

    companion object {
        fun graph(context: Context): FrameGraph = (context.applicationContext as FrameApp).graph
    }
}
