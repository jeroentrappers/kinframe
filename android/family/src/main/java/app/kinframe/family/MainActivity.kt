package app.kinframe.family

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.kinframe.core.design.KinDensity
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.design.KinframeTheme
import app.kinframe.family.notify.Notifications
import app.kinframe.family.ui.AppViewModel
import app.kinframe.family.ui.FamilyRoot
import app.kinframe.family.util.DeepLinks

/** The single activity: launcher, share target (photos and videos), frame and invite links, notification taps. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as FamilyApp).graph
    private lateinit var app: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app = ViewModelProvider(this, viewModelFactory { initializer { AppViewModel(graph) } })[AppViewModel::class.java]
        if (savedInstanceState == null) handle(intent)
        setContent {
            KinframeTheme(density = KinDensity.Phone, colors = if (isSystemInDarkTheme()) KinPalettes.dark else KinPalettes.light) {
                FamilyRoot(graph, app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = sharedUris(intent)
                if (uris.isNotEmpty()) graph.incomingShare.value = uris
            }
            Intent.ACTION_VIEW -> DeepLinks.parse(intent.dataString)?.let { app.onLink(it) }
        }
        intent.getStringExtra(Notifications.EXTRA_TARGET)?.let { target ->
            graph.openTarget.value = OpenTarget(
                target,
                sendId = intent.getStringExtra(Notifications.EXTRA_SEND_ID),
                frameId = intent.getStringExtra(Notifications.EXTRA_FRAME_ID),
                placementId = intent.getStringExtra(Notifications.EXTRA_PLACEMENT_ID),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
        } else {
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(out::add)
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::addAll)
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
        return out.distinct()
    }
}
