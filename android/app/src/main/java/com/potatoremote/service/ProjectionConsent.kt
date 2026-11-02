package com.potatoremote.service

import android.content.Intent

/**
 * Le consentement MediaProjection est un jeton à usage unique.
 * Android interdit de le sérialiser dans un Intent : on le passe en mémoire,
 * puis on l'efface dès que le service l'a consommé.
 */
object ProjectionConsent {
    data class Consent(val resultCode: Int, val data: Intent)

    private var current: Consent? = null

    @Synchronized
    fun save(resultCode: Int, data: Intent) {
        current = Consent(resultCode, data)
    }

    @Synchronized
    fun take(): Consent? {
        val value = current
        current = null
        return value
    }

    @Synchronized
    fun clear() {
        current = null
    }
}
