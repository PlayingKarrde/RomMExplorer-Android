package com.playingkarrde.rommexplorer.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Controller buttons that aren't plain navigation. A/B are turned into confirm/back by MainActivity. */
enum class PadAction { X, Y, L1, R1, L2, R2, Start, Select }

fun padActionFor(keyCode: Int): PadAction? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_X -> PadAction.X
    KeyEvent.KEYCODE_BUTTON_Y -> PadAction.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> PadAction.L1
    KeyEvent.KEYCODE_BUTTON_R1 -> PadAction.R1
    KeyEvent.KEYCODE_BUTTON_L2 -> PadAction.L2
    KeyEvent.KEYCODE_BUTTON_R2 -> PadAction.R2
    KeyEvent.KEYCODE_PAGE_UP -> PadAction.L2
    KeyEvent.KEYCODE_PAGE_DOWN -> PadAction.R2
    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadAction.Start
    KeyEvent.KEYCODE_BUTTON_SELECT -> PadAction.Select
    else -> null
}

/** Routes [PadAction]s to the most recently registered handler that accepts them (e.g. a dialog before its screen). */
class PadDispatcher {
    private val handlers = ArrayList<(PadAction) -> Boolean>()

    fun dispatch(action: PadAction): Boolean {
        for (h in handlers.asReversed().toList()) if (h(action)) return true
        return false
    }

    fun register(h: (PadAction) -> Boolean): () -> Unit {
        handlers.add(h)
        return { handlers.remove(h) }
    }
}

val LocalPad = staticCompositionLocalOf { PadDispatcher() }

/** Registers [onAction] while this composable is in the composition. Return true to consume. */
@Composable
fun PadHandler(onAction: (PadAction) -> Boolean) {
    val pad = LocalPad.current
    val current by rememberUpdatedState(onAction)
    DisposableEffect(pad) {
        val unregister = pad.register { current(it) }
        onDispose { unregister() }
    }
}

/** A clearly visible focus ring (and optional scale) for controller users. Put it before `clickable`. */
fun Modifier.focusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1f,
    onFocus: (Boolean) -> Unit = {},
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    this
        .onFocusChanged {
            if (focused != it.isFocused) {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
        }
        .graphicsLayer { scaleX = s; scaleY = s }
        .border(if (focused) 3.dp else 0.dp, if (focused) Palette.Accent2 else Palette.Background.copy(alpha = 0f), shape)
}
