package com.shijiannote.app

import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/** Connects to the real emulator IME; Compose text injection alone does not prove keyboard layout. */
@SdkSuppress(minSdkVersion = 30)
class DiaryKeyboardInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun keyboardGeometry(): Pair<Int, Int> {
        var result = 0 to 0
        rule.runOnUiThread {
            val window = rule.activity.window
            val insets = window.decorView.rootWindowInsets
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                result = window.windowManager.currentWindowMetrics.bounds.height() to
                    insets.getInsets(WindowInsets.Type.ime()).bottom
            }
        }
        return result
    }

    /** Android 17 emulator advertises stylus devices; inject an explicit finger, not stylus handwriting. */
    private fun tapFieldWithFinger() {
        val bounds = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = bounds.center.x; y = bounds.center.y; pressure = 1f; size = 1f })
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(time, time + index * 40L, action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun closePreviousImeSession() {
        rule.runOnUiThread {
            val window = rule.activity.window
            window.insetsController?.hide(WindowInsets.Type.ime())
            rule.activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        automation.waitForIdle(350, 5_000)
    }

    /** Insets can be ready while Gboard still exposes a stale white handwriting canvas. */
    private fun ordinaryKeyboardHasPainted(): Boolean {
        val (_, ime) = keyboardGeometry()
        if (ime <= 0) return false
        val screenshot = automation.takeScreenshot() ?: return false
        try {
            val density = rule.activity.resources.displayMetrics.density
            val top = (screenshot.height - ime + 40 * density).toInt().coerceAtLeast(0)
            val bottom = (screenshot.height - 40 * density).toInt()
            if (top >= bottom) return false
            var total = 0
            var painted = 0
            // Exclude the left floating handwriting controls and gesture navigation.
            for (y in top until bottom step 12) for (x in screenshot.width / 4 until screenshot.width * 19 / 20 step 12) {
                val pixel = screenshot.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                total++
                if (minOf(red, green, blue) < 238) painted++
            }
            return total > 0 && painted.toFloat() / total > .035f
        } finally { screenshot.recycle() }
    }

    private fun assertToolbarAtKeyboard() {
        val (height, ime) = keyboardGeometry()
        val density = rule.activity.resources.displayMetrics.density
        assertTrue("The actual system IME must occupy screen space", ime > 100 * density)
        val toolbar = rule.onNodeWithContentDescription("发送片段").fetchSemanticsNode().boundsInWindow
        val keyboardTop = height - ime
        Log.i("DiaryKeyboardQA", "screen=" + height + " ime=" + ime + " toolbar=" + toolbar + " keyboardTop=" + keyboardTop)
        assertTrue("Functional toolbar should sit immediately above IME: " + toolbar + " / " + keyboardTop,
            abs(toolbar.bottom - keyboardTop) <= 16 * density)
        assertTrue("Toolbar must remain fully above IME", toolbar.top < keyboardTop && toolbar.bottom <= keyboardTop + 4 * density)
    }

    private fun cursorAndViewport(): Pair<Rect, Rect> {
        val input = rule.onNode(hasSetTextAction())
        val layouts = mutableListOf<TextLayoutResult>()
        input.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        val node = input.fetchSemanticsNode()
        val cursor = layout.getCursorRect(layout.layoutInput.text.length).translate(node.positionInRoot)
        return cursor to node.boundsInRoot
    }

    @Test fun realKeyboardKeepsToolbarAndLongTextCaretVisible() {
        val originalSetting = shell("settings get secure show_ime_with_hard_keyboard")
        val originalHandwriting = shell("settings get secure stylus_handwriting_enabled")
        lateinit var model: WorkspaceModel
        var note: NoteNode? = null
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("settings put secure stylus_handwriting_enabled 0")
            closePreviousImeSession()
            rule.runOnUiThread { model = WorkspaceModel(rule.activity.application as Application) }
            runBlocking { model.spacesReady.first { it } }
            note = runBlocking {
                val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
                val date = generateSequence(LocalDate.of(1986, 1, 1)) { it.plusDays(1) }.first { dayMillis(it) !in occupied }
                NoteNode(id = "keyboard-ui-" + UUID.randomUUID(), kind = "diary", day = dayMillis(date))
                    .also { model.notes.put(it); model.nodes.first { nodes -> nodes.any { row -> row.id == it.id } } }
            }
            val current = note!!
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(current, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("键盘").performClick()
            tapFieldWithFinger()
            rule.waitUntil(10_000) { keyboardGeometry().second > 0 }
            if (!ordinaryKeyboardHasPainted()) {
                closePreviousImeSession()
                tapFieldWithFinger()
                rule.runOnUiThread { rule.activity.window.insetsController?.show(WindowInsets.Type.ime()) }
            }
            rule.waitUntil(10_000) { ordinaryKeyboardHasPainted() }
            automation.waitForIdle(350, 5_000)
            rule.onNode(hasSetTextAction()).performTextInput("触物有感，随手记下这一刻。")
            rule.waitForIdle()
            assertToolbarAtKeyboard()

            val longText = (1..40).joinToString("\n") { index ->
                "第" + index + "段：沿着这条路慢慢走过，看见树影、花草和自己的心情。"
            } + "\n最后这一行的光标也应该保持可见。"
            rule.onNode(hasSetTextAction()).performTextReplacement(longText)
            val density = rule.activity.resources.displayMetrics.density
            rule.waitUntil(5_000) {
                runCatching {
                    val (cursor, viewport) = cursorAndViewport()
                    viewport.height > 0 && cursor.top >= viewport.top - 8 * density &&
                        cursor.bottom <= viewport.bottom + 8 * density
                }.getOrDefault(false)
            }
            assertToolbarAtKeyboard()
            val (cursor, viewport) = cursorAndViewport()
            val format = rule.onNodeWithContentDescription("加粗").fetchSemanticsNode().boundsInRoot
            Log.i("DiaryKeyboardQA", "long cursor=" + cursor + " visibleInput=" + viewport + " format=" + format)
            assertTrue("Caret must stay above the formatting row", cursor.bottom <= format.top + 8 * density)
            val background = rule.onNodeWithTag("diary-road-background").fetchSemanticsNode().boundsInRoot
            val editor = rule.onNodeWithTag("diary-composer-editor").fetchSemanticsNode().boundsInRoot
            assertTrue("Long editor should leave only a narrow strip of background", background.height in 6 * density..10 * density)
            assertTrue("Editor must stay below the background top", editor.top >= background.top + 6 * density)
            assertTrue("Long text remains a draft", model.currentDiary(current.day!!).diaryMoments().isEmpty())
            assertEquals(longText, model.currentDiary(current.day!!).diaryInboxItems().single().moment!!.text)
            rule.waitUntil(10_000) { ordinaryKeyboardHasPainted() }
            automation.waitForIdle(350, 5_000)
            shell("screencap -p /sdcard/shijian-diary-keyboard-v7.png")
            assertTrue("Real device screenshot must be saved", (shell("stat -c %s /sdcard/shijian-diary-keyboard-v7.png").toLongOrNull() ?: 0) > 0)
            closePreviousImeSession()
            rule.waitUntil(5_000) { keyboardGeometry().second == 0 }
            rule.waitUntil(5_000) {
                val strip = rule.onNodeWithTag("diary-road-background").fetchSemanticsNode().boundsInRoot
                strip.height in 6 * density..10 * density
            }
            shell("screencap -p /sdcard/shijian-diary-editor-expanded.png")
        } catch (failure: Throwable) {
            runCatching { shell("screencap -p /sdcard/shijian-diary-keyboard-v7-failure.png") }
            throw failure
        } finally {
            if (originalSetting == "null" || originalSetting.isBlank()) shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard " + originalSetting.toInt())
            if (originalHandwriting == "null" || originalHandwriting.isBlank()) shell("settings delete secure stylus_handwriting_enabled")
            else shell("settings put secure stylus_handwriting_enabled " + originalHandwriting.toInt())
            note?.let { fixture ->
                rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
                runBlocking {
                    model.flush(fixture.id)
                    model.notes.remove(listOf(fixture.id))
                    model.notes.removeVersions(listOf(fixture.id))
                }
            }
        }
    }
}

