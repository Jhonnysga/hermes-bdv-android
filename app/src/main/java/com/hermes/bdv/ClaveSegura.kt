package com.hermes.bdv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacena la clave bancaria de forma CIFRADA en el dispositivo
 * (AndroidX Security: EncryptedSharedPreferences con MasterKey AES256).
 *
 * La clave se configura UNA VEZ desde la app (petición del usuario 29/09/2026)
 * y el servicio la lee al iniciar cada ejecución. Nunca se envía a Telegram,
 * nunca aparece en logs y nunca sale del teléfono.
 */
object ClaveSegura {
    private const val TAG = "HermesClave"
    private const val PREFS = "hermes_clave_cifrada"
    private const val K_CLAVE = "clave"

    @Volatile
    private var cache: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        cache?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { cache = it }
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo abrir el almacén cifrado", e)
            null
        }
    }

    /** Guarda la clave cifrada. Retorna true si se guardó. */
    fun guardar(context: Context, clave: String): Boolean {
        val c = clave.trim()
        if (c.isEmpty()) return false
        return try {
            prefs(context)?.edit()?.putString(K_CLAVE, c)?.commit() == true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo guardar la clave", e)
            false
        }
    }

    /** Lee la clave (solo el servicio la usa al ejecutar). */
    fun leer(context: Context): String {
        return try {
            prefs(context)?.getString(K_CLAVE, "").orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo leer la clave", e)
            ""
        }
    }

    /** true si hay una clave configurada. */
    fun configurada(context: Context): Boolean = leer(context).isNotEmpty()

    /** Borra la clave del almacén cifrado. */
    fun borrar(context: Context) {
        try {
            prefs(context)?.edit()?.remove(K_CLAVE)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo borrar la clave", e)
        }
    }
}
