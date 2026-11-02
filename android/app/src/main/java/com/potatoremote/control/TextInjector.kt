package com.potatoremote.control

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Saisie distante via AccessibilityService.
 *
 * ACTION_SET_TEXT est l'API publique prévue pour insérer du texte dans le champ
 * actif. Elle ne fonctionne pas partout : champs sécurisés, WebView, éditeurs
 * personnalisés et certaines applications peuvent la refuser. Dans ce cas le
 * refus est renvoyé au spectateur, sans prétendre que la saisie a réussi.
 */
object TextInjector {
    data class Result(val ok: Boolean, val reason: String? = null)

    fun inject(service: AccessibilityService, op: String, text: String, count: Int): Result {
        val node = findEditable(service)
            ?: return Result(false, "Aucun champ de saisie actif sur le téléphone.")

        return when (op) {
            "insert" -> {
                if (text.isEmpty()) return Result(false, "Texte vide.")
                val existing = node.text?.toString().orEmpty()
                val start = node.textSelectionStart.takeIf { it in 0..existing.length } ?: existing.length
                val end = node.textSelectionEnd.takeIf { it in start..existing.length } ?: start
                val inserted = existing.substring(0, start) + text + existing.substring(end)
                setText(node, inserted, start + text.length)
            }
            "set" -> setText(node, text, text.length)
            "backspace" -> backspace(node, count.coerceIn(1, 200))
            else -> Result(false, "Opération de texte inconnue.")
        }
    }

    fun pressKey(service: AccessibilityService, key: String): Result = when (key) {
        "enter" -> enter(service)
        "backspace" -> {
            val node = findEditable(service) ?: return Result(false, "Aucun champ de saisie actif.")
            backspace(node, 1)
        }
        "escape", "back" -> Result(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), "Retour refusé par Android.")
        "home" -> Result(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME), "Accueil refusé par Android.")
        else -> Result(false, "Touche non prise en charge : $key")
    }

    private fun enter(service: AccessibilityService): Result {
        val node = findEditable(service)
        if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val handled = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (handled) return Result(true)
        }
        // Repli : insère un retour à la ligne dans le champ actif.
        val target = node ?: return Result(false, "Aucun champ de saisie actif.")
        val existing = target.text?.toString().orEmpty()
        return setText(target, existing + "\n", existing.length + 1)
    }

    private fun backspace(node: AccessibilityNodeInfo, count: Int): Result {
        var existing = node.text?.toString().orEmpty()
        if (existing.isEmpty()) return Result(false, "Le champ est déjà vide.")

        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        var cursor = if (start in 0..existing.length && end in 0..existing.length && start <= end) start else existing.length
        val selectionEnd = if (end in cursor..existing.length) end else cursor

        var remaining = count
        var newText = existing
        if (selectionEnd > cursor) {
            newText = existing.removeRange(cursor, selectionEnd)
        } else {
            while (remaining > 0 && cursor > 0) {
                var previous = cursor - 1
                if (previous > 0 && Character.isLowSurrogate(newText[previous]) && Character.isHighSurrogate(newText[previous - 1])) {
                    previous -= 1
                }
                newText = newText.removeRange(previous, cursor)
                cursor = previous
                remaining -= 1
            }
        }
        if (newText == existing) return Result(false, "Le champ est déjà vide.")
        return setText(node, newText, cursor)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String, cursor: Int): Result {
        if (!node.isEditable) return Result(false, "Le champ actif n'accepte pas la saisie distante.")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val applied = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!applied) {
            return Result(false, "Cette application refuse la saisie distante (champ protégé).")
        }
        try {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor.coerceAtLeast(0))
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor.coerceAtLeast(0))
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        } catch (_: Throwable) {
            // La sélection est un confort : le texte est déjà inséré.
        }
        return Result(true)
    }

    private fun findEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        try {
            for (window in service.windows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable) return focused
                if (fallback == null) fallback = findEditableInTree(root, 0)
            }
        } catch (_: Throwable) {
            // Certaines fenêtres protégées refusent l'énumération.
        }
        return fallback
    }

    private fun findEditableInTree(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findEditableInTree(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
