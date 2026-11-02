package com.potatoremote.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.potatoremote.ui.ShareState

/**
 * Service d'accessibilité volontairement activé par le propriétaire du téléphone.
 * Il ne fait rien tant qu'aucune commande n'arrive d'un spectateur autorisé.
 */
class PotatoAccessibilityService : AccessibilityService() {
    private var touchInjector: TouchInjector? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        touchInjector = TouchInjector(this)
        AccessibilityBridge.attach(this)
        ShareState.accessibilityReady.value = true
        ShareState.log("Contrôle distant autorisé (accessibilité active)", "success")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aucune surveillance : Potato Remote ne lit pas le contenu de l'écran.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        touchInjector?.release()
        touchInjector = null
        AccessibilityBridge.detach(this)
        ShareState.accessibilityReady.value = false
        ShareState.log("Contrôle distant désactivé", "warn")
        super.onDestroy()
    }

    fun touch(): TouchInjector? = touchInjector
}
