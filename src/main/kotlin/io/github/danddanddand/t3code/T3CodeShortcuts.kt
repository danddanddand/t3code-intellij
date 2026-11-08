package io.github.danddanddand.t3code

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Sends T3 Code's keyboard shortcuts to the page while the pane has focus.
 *
 * The IDE offers every key press to its keymap before the embedded browser sees it, so shortcuts both apps use (Cmd+K,
 * Cmd+Shift+A, Cmd+Shift+O, …) would run the IDE action instead of T3 Code's. Shortcuts registered on a focused
 * component take precedence over the global keymap, which is how JCEF itself routes copy, paste and undo to the page.
 * This registers T3 Code's shortcuts on the pane and hands each matching key press straight to Chromium. IDE
 * shortcuts that T3 Code does not use are unaffected.
 */
internal object T3CodeShortcuts {
    // Declared first: DEFAULTS and LEFT_TO_JCEF below are parsed while the object initializes.
    private val NAMED_KEYS = mapOf(
        "[" to KeyEvent.VK_OPEN_BRACKET, "]" to KeyEvent.VK_CLOSE_BRACKET, "=" to KeyEvent.VK_EQUALS,
        "-" to KeyEvent.VK_MINUS, "," to KeyEvent.VK_COMMA, "." to KeyEvent.VK_PERIOD, "/" to KeyEvent.VK_SLASH,
        "\\" to KeyEvent.VK_BACK_SLASH, ";" to KeyEvent.VK_SEMICOLON, "'" to KeyEvent.VK_QUOTE, "`" to KeyEvent.VK_BACK_QUOTE,
        "enter" to KeyEvent.VK_ENTER, "return" to KeyEvent.VK_ENTER, "escape" to KeyEvent.VK_ESCAPE, "esc" to KeyEvent.VK_ESCAPE,
        "tab" to KeyEvent.VK_TAB, "space" to KeyEvent.VK_SPACE, "backspace" to KeyEvent.VK_BACK_SPACE,
        "delete" to KeyEvent.VK_DELETE, "arrowup" to KeyEvent.VK_UP, "arrowdown" to KeyEvent.VK_DOWN,
        "arrowleft" to KeyEvent.VK_LEFT, "arrowright" to KeyEvent.VK_RIGHT, "up" to KeyEvent.VK_UP,
        "down" to KeyEvent.VK_DOWN, "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT, "home" to KeyEvent.VK_HOME,
        "end" to KeyEvent.VK_END, "pageup" to KeyEvent.VK_PAGE_UP, "pagedown" to KeyEvent.VK_PAGE_DOWN,
    )
    private val FUNCTION_KEY = Regex("f([1-9]|1[0-9]|2[0-4])")

    /**
     * T3 Code's default bindings (packages/shared/src/keybindings.ts), minus desktop-only ones and plain letters.
     * The user's own keybindings.json, when present, adds to these.
     */
    private val DEFAULTS = listOf(
        "mod+b", "f6", "shift+f6", "mod+f6", "mod+shift+f6", "mod+[", "mod+]", "mod+j", "mod+alt+b", "mod+shift+b",
        "mod+t", "mod+d", "mod+shift+d", "mod+n", "mod+w", "mod+shift+t", "mod+shift+j", "mod+r", "mod+l", "mod+=",
        "mod++", "mod+-", "mod+0", "mod+k", "mod+p", "mod+shift+f", "mod+u", "mod+alt+a", "mod+alt+shift+a",
        "mod+alt+shift+t", "mod+s", "mod+shift+enter", "alt+arrowup", "mod+enter", "mod+alt+enter", "mod+shift+o",
        "mod+shift+n", "mod+alt+n", "mod+f", "mod+shift+m", "mod+shift+h", "mod+shift+e", "mod+shift+a",
        "mod+shift+x", "mod+shift+g", "mod+shift+l", "mod+shift+k", "mod+shift+arrowup", "mod+shift+arrowdown",
        "mod+o", "mod+shift+[", "mod+shift+]", "mod+shift+c", "mod+shift+s", "mod+shift+p", "mod+shift+1",
        "mod+shift+2", "mod+shift+3", "mod+shift+4",
    )

    /** JCEF already routes these editing keys to the page itself; registering them again would make them ambiguous. */
    private val LEFT_TO_JCEF = listOf("mod+z", "mod+shift+z", "mod+y", "mod+c", "mod+v", "mod+x", "mod+a")
        .mapNotNull(::parse).toSet()

    fun install(target: JComponent, browser: JBCefBrowser, parent: Disposable) {
        val strokes = (DEFAULTS + userKeys()).mapNotNull(::parse).filter(::forwardable).toSet() - LEFT_TO_JCEF
        val shortcuts = strokes.map { KeyboardShortcut(it, null) }
        ForwardToPage(browser).registerCustomShortcutSet(CustomShortcutSet(*shortcuts.toTypedArray()), target, parent)
    }

    /** Hands the key press to Chromium; the release that follows reaches the browser component as usual. */
    private class ForwardToPage(private val browser: JBCefBrowser) : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // Only an off-screen browser takes key events from the IDE; a windowed one gets them natively first.
            e.presentation.isEnabled = T3CodeSettings.instance.forwardShortcuts && !browser.isDisposed &&
                browser.isOffScreenRendering && browser.component.isShowing
        }

        override fun actionPerformed(e: AnActionEvent) {
            val key = e.inputEvent as? KeyEvent ?: return
            try {
                browser.cefBrowser.sendKeyEvent(key)
            } catch (t: Throwable) {
                thisLogger().warn("Could not send a shortcut to the T3 Code page", t)
            }
        }
    }

    /** Puts the forwarder ahead of IDE actions bound to the same keys, whatever order the dispatcher collected them in. */
    class Promoter : ActionPromoter {
        override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
            actions.filterIsInstance<ForwardToPage>()
    }

    /** Keys from the user's T3 Code keybindings.json, which the server serves to the app alongside the defaults. */
    private fun userKeys(): List<String> {
        val baseDir = System.getenv("T3CODE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".t3")
        val file = baseDir.resolve("userdata/keybindings.json")
        return try {
            if (!Files.isRegularFile(file)) return emptyList()
            JsonParser.parseString(Files.readString(file)).asJsonArray.mapNotNull { element ->
                val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val key = rule.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val whenClause = rule.get("when")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                key.takeUnless { "isDesktop" in whenClause && "!isDesktop" !in whenClause }
            }
        } catch (e: Exception) {
            thisLogger().info("Could not read T3 Code keybindings from $file; using the defaults", e)
            emptyList()
        }
    }

    /** Plain and Shift-only keys are typing, not shortcuts; only modified keys and function keys are taken from the IDE. */
    private fun forwardable(stroke: KeyStroke): Boolean {
        val modifiers = stroke.modifiers and (InputEvent.META_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        return modifiers != 0 || stroke.keyCode in KeyEvent.VK_F1..KeyEvent.VK_F12 || stroke.keyCode in KeyEvent.VK_F13..KeyEvent.VK_F24
    }

    /** Parses T3 Code's key syntax ("mod+shift+[", "mod++", "alt+arrowup") into a key stroke; null when unknown. */
    internal fun parse(spec: String): KeyStroke? {
        val text = spec.trim().lowercase()
        if (text.isEmpty()) return null
        // "mod++" means mod and the plus key; splitting on '+' alone would lose the key.
        val (modifierPart, keyPart) = if (text.endsWith("++")) text.dropLast(2) to "+"
        else text.substringBeforeLast('+', "") to text.substringAfterLast('+')
        var modifiers = 0
        for (token in modifierPart.split('+').filter { it.isNotEmpty() }) {
            modifiers = modifiers or when (token) {
                "mod" -> if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
                "cmd", "meta", "super" -> InputEvent.META_DOWN_MASK
                "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK
                "alt", "option" -> InputEvent.ALT_DOWN_MASK
                "shift" -> InputEvent.SHIFT_DOWN_MASK
                else -> return null
            }
        }
        val keyCode = when {
            keyPart == "+" -> KeyEvent.VK_EQUALS.also { modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK }
            keyPart.length == 1 && keyPart[0] in 'a'..'z' -> KeyEvent.VK_A + (keyPart[0] - 'a')
            keyPart.length == 1 && keyPart[0] in '0'..'9' -> KeyEvent.VK_0 + (keyPart[0] - '0')
            FUNCTION_KEY.matches(keyPart) -> functionKey(keyPart.drop(1).toInt())
            else -> NAMED_KEYS[keyPart] ?: return null
        }
        return KeyStroke.getKeyStroke(keyCode, modifiers)
    }

    private fun functionKey(n: Int) = if (n <= 12) KeyEvent.VK_F1 + n - 1 else KeyEvent.VK_F13 + n - 13
}
