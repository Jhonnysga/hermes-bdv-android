package com.hermes.bdv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Recibe los eventos que emite [HermesAccessibilityService] durante la
 * ejecución y los refleja en Telegram + el registro en pantalla.
 *
 * Acción: com.hermes.bdv.EVENTO
 * Extras:
 *   - "tipo":    "pantalla" | "error" | "exito"
 *   - "mensaje": texto descriptivo
 *   - "captura":      ByteArray PNG opcional (solo "exito")
 *   - "captura_path": ruta a un PNG en caché opcional (solo "exito",
 *                     preferido para imágenes grandes y evitar
 *                     TransactionTooLargeException)
 *
 * Reglas de notificación (petición del usuario):
 *   - "pantalla": solo texto por Telegram, sin captura.
 *   - "error":    solo texto por Telegram ("⚠ ..."), sin captura.
 *   - "exito":    foto por Telegram con el mensaje
 *                 "🎉 ¡Felicidades, lograste comprar divisas!".
 *                 Si no hay imagen, se envía solo el texto.
 *
 * Se registra de forma dinámica en MainActivity (no va en el Manifest)
 * porque solo tiene sentido mientras la app está en ejecución.
 */
class HermesEventReceiver(
    private val onLog: ((String) -> Unit)? = null
) : BroadcastReceiver() {

    companion object {
        const val ACCION_EVENTO = "com.hermes.bdv.EVENTO"
        const val EXTRA_TIPO = "tipo"
        const val EXTRA_MENSAJE = "mensaje"
        const val EXTRA_CAPTURA = "captura"
        const val EXTRA_CAPTURA_PATH = "captura_path"

        const val TIPO_PANTALLA = "pantalla"
        const val TIPO_ERROR = "error"
        const val TIPO_EXITO = "exito"

        const val CAPTION_EXITO = "🎉 ¡Felicidades, lograste comprar divisas!"

        /** Límite prudente para una imagen enviada por extras (≈4 MB). */
        private const val MAX_PNG_BYTES = 4_000_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACCION_EVENTO) return
        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: return
        val mensaje = intent.getStringExtra(EXTRA_MENSAJE).orEmpty()

        val token = Config.getTokenTelegram(context)
        val chatId = Config.getChatTelegram(context)
        val telegramListo = token.isNotBlank() && chatId != 0L
        if (!telegramListo) {
            onLog?.invoke("(Telegram sin configurar) $mensaje")
            return
        }

        when (tipo) {
            TIPO_PANTALLA -> {
                if (mensaje.isNotBlank()) {
                    if (Config.isAvisosActivos(context)) {
                        TelegramNotifier.enviarTexto(token, chatId, mensaje)
                    }
                    onLog?.invoke(mensaje)
                }
            }
            TIPO_ERROR -> {
                if (mensaje.isNotBlank()) {
                    TelegramNotifier.enviarTexto(token, chatId, "⚠ $mensaje")
                    onLog?.invoke("⚠ $mensaje")
                }
            }
            TIPO_EXITO -> {
                val png = leerCaptura(intent)
                if (png != null) {
                    TelegramNotifier.enviarFoto(token, chatId, png, CAPTION_EXITO)
                } else {
                    TelegramNotifier.enviarTexto(token, chatId, CAPTION_EXITO)
                }
                onLog?.invoke(CAPTION_EXITO)
                // La ejecución terminó. (La clave vive cifrada en ClaveSegura;
                // no hay nada que limpiar de memoria.)
            }
        }
    }

    private fun leerCaptura(intent: Intent): ByteArray? {
        intent.getByteArrayExtra(EXTRA_CAPTURA)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val path = intent.getStringExtra(EXTRA_CAPTURA_PATH) ?: return null
        return try {
            val f = File(path)
            if (f.exists() && f.length() in 1..MAX_PNG_BYTES) f.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }
}
