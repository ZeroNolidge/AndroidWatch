package com.androidwatch.phonelink

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * True while an on-screen keyboard covers part of the window. On a 2" watch
 * the keyboard takes most of the screen, so screens switch to a compact
 * layout while it's up. (Works on Android 8, unlike the newer insets APIs.)
 */
@Composable
fun rememberKeyboardVisible(): Boolean {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            val height = view.rootView.height
            visible = height > 0 && height - frame.bottom > height * 0.15
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return visible
}

/** Returns a function that hides the keyboard and takes focus off the text field. */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return remember(keyboard, focus) {
        {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
}
