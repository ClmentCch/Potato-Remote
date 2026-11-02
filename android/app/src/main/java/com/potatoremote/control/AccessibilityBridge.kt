package com.potatoremote.control

import java.lang.ref.WeakReference

/** Point d'accès unique au service d'accessibilité (consentement explicite). */
object AccessibilityBridge {
    @Volatile
    private var reference: WeakReference<PotatoAccessibilityService>? = null

    fun attach(service: PotatoAccessibilityService) {
        reference = WeakReference(service)
    }

    fun detach(service: PotatoAccessibilityService) {
        val current = reference?.get()
        if (current == null || current === service) reference = null
    }

    fun service(): PotatoAccessibilityService? = reference?.get()

    val isReady: Boolean
        get() = reference?.get() != null
}
