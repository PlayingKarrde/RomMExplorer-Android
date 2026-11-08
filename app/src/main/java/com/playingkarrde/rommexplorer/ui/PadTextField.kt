package com.playingkarrde.rommexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Edit state for a [PadTextField]. Moving focus onto the field with the D-pad only highlights it;
 * the keyboard opens when the user selects it (A / tap) or code calls [startEditing] (e.g. Y for search).
 */
@Stable
class PadFieldState {
    var editing by mutableStateOf(false)
        internal set
    internal val outer = FocusRequester()
    internal val inner = FocusRequester()
    /** Set when editing ends on purpose (IME action / back), so focus returns to the highlighted field. */
    internal var refocus by mutableStateOf(false)
    internal var afterRefocus: (() -> Unit)? = null

    fun startEditing() {
        editing = true
    }

    /** Stops editing, puts focus back on the field, then runs [then] (e.g. move to the next field). */
    fun stopEditing(then: (() -> Unit)? = null) {
        if (!editing) return
        afterRefocus = then
        refocus = true
        editing = false
    }

    /** Focuses the field without opening the keyboard. */
    fun requestFocus() {
        runCatching { if (editing) inner.requestFocus() else outer.requestFocus() }
    }
}

@Composable
fun rememberPadFieldState() = remember { PadFieldState() }

@Composable
fun PadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: PadFieldState = rememberPadFieldState(),
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Called for the IME action (Done/Next/Search…) after editing has stopped and focus is back on the field. */
    onImeAction: () -> Unit = {},
    shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var innerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(state.editing) {
        if (state.editing) {
            runCatching { state.inner.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            if (state.refocus) {
                state.refocus = false
                runCatching { state.outer.requestFocus() }
                state.afterRefocus?.invoke()
                state.afterRefocus = null
            }
        }
    }

    // B / back while typing: first press closes the IME (system), the next stops editing.
    BackHandler(enabled = state.editing) { state.stopEditing() }

    val endAction = { state.stopEditing(onImeAction) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = true,
            shape = shape,
            colors = colors,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(
                onDone = { endAction() }, onNext = { endAction() }, onGo = { endAction() },
                onSearch = { endAction() }, onSend = { endAction() },
            ),
            modifier = Modifier
                .focusRequester(state.inner)
                .focusProperties { canFocus = state.editing }
                .onFocusChanged {
                    // Focus left while typing (touch elsewhere, D-pad up/down): just stop editing.
                    if (innerFocused && !it.isFocused && state.editing) state.editing = false
                    innerFocused = it.isFocused
                },
        )
        if (!state.editing) {
            // Covers the field while not editing: takes D-pad focus (with a visible ring) and taps,
            // and turns "select" into "start editing".
            Box(
                Modifier
                    .matchParentSize()
                    .focusRequester(state.outer)
                    .focusRing(shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.startEditing() },
            )
        }
    }
}
