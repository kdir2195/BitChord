package com.music.bitchord.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

private data class IslandUi(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
)

/** Pemilik lifecycle palsu supaya ComposeView bisa hidup di luar Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

object IslandOverlay {

    private val ui = mutableStateOf(IslandUi())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update(player)
    }

    /** Panggil di AKHIR PlaybackService.onCreate(). */
    fun start(context: Context) {
        val app = context.applicationContext
        if (view != null || future != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            update(c)
            show(app)
        }, ContextCompat.getMainExecutor(app))
    }

    /** Panggil di PlaybackService.onDestroy(). */
    fun stop(context: Context) {
        val app = context.applicationContext
        view?.let {
            try {
                app.getSystemService(WindowManager::class.java).removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
        owner?.destroy()
        owner = null
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun update(p: Player) {
        val m = p.mediaMetadata
        ui.value = IslandUi(
            title = m.title?.toString().orEmpty(),
            artist = (m.artist ?: m.albumArtist)?.toString().orEmpty(),
            playing = p.isPlaying,
        )
    }

    private fun show(app: Context) {
        // Izin "Tampil di atas aplikasi lain" harus sudah diberikan manual.
        if (view != null || !Settings.canDrawOverlays(app)) return
        val o = OverlayOwner()
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                Island(
                    s = ui.value,
                    onPrev = { controller?.seekToPreviousMediaItem() },
                    onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    onNext = { controller?.seekToNextMediaItem() },
                )
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * app.resources.displayMetrics.density).toInt()
        }
        try {
            app.getSystemService(WindowManager::class.java).addView(v, lp)
            view = v
            owner = o
        } catch (e: Exception) {
            o.destroy()
        }
    }
}

@Composable
private fun Island(
    s: IslandUi,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // Setiap ganti lagu: melebar 4 detik, lalu mengecil lagi.
    LaunchedEffect(s.title) {
        if (s.title.isNotEmpty()) {
            expanded = true
            delay(4000)
            expanded = false
        }
    }

    if (s.title.isEmpty() || (!s.playing && !expanded)) return

    Box(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (expanded) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(min = 220.dp, max = 300.dp),
            ) {
                BasicText(
                    text = s.title,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = s.artist,
                    style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    IslandButton("\u23EE", onPrev)
                    IslandButton(if (s.playing) "\u23F8" else "\u25B6", onToggle)
                    IslandButton("\u23ED", onNext)
                }
            }
        } else {
            BasicText(
                text = "\u266A ${s.title}",
                style = TextStyle(color = Color.White, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

@Composable
private fun IslandButton(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = Color.White, fontSize = 20.sp),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
