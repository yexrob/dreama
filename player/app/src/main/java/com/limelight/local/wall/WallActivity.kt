package com.limelight.local.wall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.limelight.LimeLog
import com.limelight.local.LocalPlayerXR
import com.limelight.local.WebDav
import com.limelight.local.airplay.AirPlayService
import com.limelight.local.dlna.CastReceiver
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.stream.StreamSessionBridge
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * depth3d's single 2D entry point: Home, Library, Cast & PC Streaming, and Settings.
 */
class WallActivity : ComponentActivity(), WallActions {
    private val model: WallModel by viewModels()
    private val pendingTab = MutableStateFlow<Tab?>(null)

    private var askedByUser = false
    private val storage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            model.refresh(LocalVideoSource.ID)
        } else if (askedByUser && !shouldShowRequestPermissionRationale(LocalVideoSource.permission)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        askedByUser = false
    }

    private var loggedHover = false
    private var loggedTouch = false

    override val hostActivity: Activity get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("base")?.let {
            WebDav.save(
                this,
                WebDav.Server(it, intent.getStringExtra("user") ?: "depth3d", intent.getStringExtra("pass") ?: ""),
            )
        }
        applyInitialTab(intent)
        if (savedInstanceState == null && !LocalVideoSource.hasPermission(this)) {
            storage.launch(LocalVideoSource.permission)
        }
        setContent { WallApp(model, this, pendingTab) }
        AirPlayService.start(this)
        window.decorView.post {
            val v = window.decorView
            LimeLog.info(
                "depth3d: wall window ${v.width}x${v.height} px, density ${resources.displayMetrics.density}, " +
                    "${v.width / DESIGN_WIDTH} px per design unit",
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyInitialTab(intent)
    }

    override fun onStart() {
        super.onStart()
        CastReceiver.hold(this)
        model.streaming.onStart()
    }

    override fun onStop() {
        super.onStop()
        model.streaming.onStop()
        CastReceiver.release(this)
    }

    override fun onDestroy() {
        if (isFinishing) {
            AirPlayService.stop(this)
        }
        super.onDestroy()
    }

    override fun onRestart() {
        super.onRestart()
        model.refreshAll()
    }

    private fun applyInitialTab(intent: Intent?) {
        if (intent?.getStringExtra(StreamSessionBridge.EXTRA_INITIAL_TAB) == StreamSessionBridge.TAB_CAST) {
            pendingTab.value = Tab.Cast
        }
    }

    override fun consumePendingTab() {
        pendingTab.value = null
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!loggedHover && ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            loggedHover = true
            LimeLog.info("depth3d: first hover ${ev.x},${ev.y} source=0x${Integer.toHexString(ev.source)} tool=${ev.getToolType(0)}")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!loggedTouch && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            loggedTouch = true
            LimeLog.info("depth3d: first touch ${ev.x},${ev.y} source=0x${Integer.toHexString(ev.source)} tool=${ev.getToolType(0)}")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun launchPlayer(info: PlaybackInfo, sourceId: String, itemId: String) {
        val play = Intent(this, LocalPlayerXR::class.java)
            .setData(Uri.parse(info.url))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(LocalPlayerXR.EXTRA_TITLE, info.title)
            .putExtra(LocalPlayerXR.EXTRA_START_MS, info.startPositionMs)
            .putExtra(LocalPlayerXR.EXTRA_SOURCE_ID, sourceId)
            .putExtra(LocalPlayerXR.EXTRA_ITEM_ID, itemId)
        info.headers["Authorization"]?.let { play.putExtra(LocalPlayerXR.EXTRA_AUTH, it) }
        startActivity(play)
    }

    override fun requestStorage() {
        askedByUser = true
        storage.launch(LocalVideoSource.permission)
    }

    override fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
