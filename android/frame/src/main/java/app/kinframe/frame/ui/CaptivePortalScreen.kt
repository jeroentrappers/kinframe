package app.kinframe.frame.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.system.CaptivePortal
import kotlinx.coroutines.delay

/**
 * The network's sign-in page in an in-app web view (product spec 4.8). Closes by itself once the probe gets through
 * to the kinframe server. The WebView exists only while this screen is open, so it costs no memory otherwise.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptivePortalScreen(c: FrameController) {
    val login by c.portal.loginUrl.collectAsState()
    val start = login ?: CaptivePortal.probeFor(c.g.prefs.serverUrl ?: app.kinframe.frame.BuildConfig.DEFAULT_SERVER)?.toString() ?: return
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            if (c.portal.check() == CaptivePortal.Result.Open) {
                c.g.network.refresh()
                c.session.value?.reconnectNow()
                c.portalOpen.value = false
                break
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).clickable { c.portalOpen.value = false }, contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.Close, stringResource(R.string.action_close))
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.portal_title), style = Kin.type.title)
                Text(stringResource(R.string.portal_body), style = Kin.type.caption, color = Kin.colors.inkSoft)
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(start)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
