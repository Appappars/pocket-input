package dev.pocketinput.ui.keyboard

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EmptyBackspaceInput(
    canForward: () -> Boolean,
    onBackspaces: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentCanForward by rememberUpdatedState(canForward)
    val currentOnBackspaces by rememberUpdatedState(onBackspaces)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            var generation = 0
            try {
                next.startInputMethod { editorInfo ->
                    val connectionGeneration = ++generation
                    EmptyBackspaceConnection(
                        target = request.createInputConnection(editorInfo),
                        canForward = { connectionGeneration == generation && currentCanForward() },
                        onBackspaces = { count -> currentOnBackspaces(count) },
                    )
                }
            } finally {
                generation++
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

private class EmptyBackspaceConnection(
    target: InputConnection,
    canForward: () -> Boolean,
    onBackspaces: (Int) -> Unit,
) : InputConnectionWrapper(target, false) {
    private val backspace = EmptyBackspace(
        canForward = {
            canForward() &&
                target.getTextBeforeCursor(1, 0)?.isEmpty() == true &&
                target.getTextAfterCursor(1, 0)?.isEmpty() == true &&
                target.getSelectedText(0).isNullOrEmpty()
        },
        onBackspaces = onBackspaces,
    )

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        backspace.deleteSurroundingText(beforeLength, afterLength) ||
            super.deleteSurroundingText(beforeLength, afterLength)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        backspace.deleteSurroundingText(beforeLength, afterLength) ||
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
            if (event.keyCode == KeyEvent.KEYCODE_DEL && event.hasNoModifiers() && backspace.delete()) {
                return true
            }
            backspace.otherEdit()
        }
        return super.sendKeyEvent(event)
    }

    override fun beginBatchEdit(): Boolean =
        super.beginBatchEdit().also { if (it) backspace.beginBatch() }

    override fun endBatchEdit(): Boolean =
        super.endBatchEdit().also { if (!it) backspace.endBatch() }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        backspace.otherEdit()
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        backspace.otherEdit()
        return super.setComposingText(text, newCursorPosition)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        backspace.otherEdit()
        return super.performContextMenuAction(id)
    }

    override fun closeConnection() {
        backspace.close()
        super.closeConnection()
    }
}

internal class EmptyBackspace(
    private val canForward: () -> Boolean,
    private val onBackspaces: (Int) -> Unit,
) {
    private var inBatch = false
    private var batchHasOtherEdits = false
    private var pending = 0
    private var closed = false

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength == 1 && afterLength == 0) return delete()
        otherEdit()
        return false
    }

    fun delete(): Boolean {
        if (closed || batchHasOtherEdits || !canForward()) return false
        if (inBatch) pending++ else onBackspaces(1)
        return true
    }

    fun beginBatch() {
        inBatch = true
    }

    fun otherEdit() {
        if (!inBatch) return
        batchHasOtherEdits = true
        pending = 0
    }

    fun endBatch() {
        val count = pending
        inBatch = false
        batchHasOtherEdits = false
        pending = 0
        // The IME may insert text later in the same batch.
        if (count > 0 && !closed && canForward()) onBackspaces(count)
    }

    fun close() {
        closed = true
        pending = 0
    }
}
