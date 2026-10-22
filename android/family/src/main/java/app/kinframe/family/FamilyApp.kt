package app.kinframe.family

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import app.kinframe.family.notify.Notifications

class FamilyApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        graph = AppGraph(this)
    }

    /** Thumbnails come from short-lived signed URLs (no auth header needed) and from content URIs while composing. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { graph.http }))
            add(VideoFrameDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
