package app.kinframe.frame.system

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import app.kinframe.frame.MainActivity
import app.kinframe.frame.R

/**
 * A floating "Back to kinframe" button while Android's own settings are open. The Denver frames have no navigation
 * bar and no buttons, so without it there is no way back from there.
 *
 * Android 6/7 allow a toast-type window without the overlay permission; later versions need "draw over other
 * apps", and without it the button is simply not shown.
 */
object ReturnButton {
    private const val TAG = "KinReturn"
    private var view: TextView? = null

    fun show(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        val type = when {
            Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(app) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(app) -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            Build.VERSION.SDK_INT < 25 -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_TOAST
            else -> return
        }
        val d = app.resources.displayMetrics.density
        val button = TextView(app).apply {
            text = app.getString(R.string.back_to_kinframe)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding((24 * d).toInt(), (14 * d).toInt(), (24 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0xF0C2603E.toInt()) }
            elevation = 8 * d
            setOnClickListener {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                hide(app)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (24 * d).toInt(); y = (24 * d).toInt()
        }
        runCatching { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(button, lp) }
            .onSuccess { view = button }
            .onFailure { Log.w(TAG, "cannot show the return button: ${it.message}") }
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        runCatching { (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }
}
