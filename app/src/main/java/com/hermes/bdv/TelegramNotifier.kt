package com.hermes.bdv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Envía mensajes y fotos al bot de Telegram usando OkHttp en un hilo de fondo.
 * Los errores se manejan en silencio con un único reintento: las notificaciones
 * nunca deben frenar ni romper el ciclo de compra.
 */
object TelegramNotifier {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val JSON: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()

    fun enviarTexto(token: String, chatId: Long, texto: String) {
        if (token.isBlank() || chatId == 0L || texto.isBlank()) return
        scope.launch {
            val cuerpo = """{"chat_id":$chatId,"text":${escapeJson(texto)}}"""
                .toRequestBody(JSON)
            publicar(token, "sendMessage", cuerpo)
        }
    }

    fun enviarFoto(token: String, chatId: Long, png: ByteArray, caption: String) {
        if (token.isBlank() || chatId == 0L) return
        if (png.isEmpty()) {
            // Sin imagen válida: degradar a solo texto
            if (caption.isNotBlank()) enviarTexto(token, chatId, caption)
            return
        }
        scope.launch {
            val tmp: File? = try {
                File.createTempFile("hermes_", ".png")
                    .apply { writeBytes(png); deleteOnExit() }
            } catch (_: Exception) {
                null
            }
            if (tmp == null) {
                enviarTexto(token, chatId, caption)
                return@launch
            }
            try {
                val cuerpo = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption)
                    .addFormDataPart(
                        "photo",
                        "captura.png",
                        tmp.asRequestBody("image/png".toMediaType())
                    )
                    .build()
                publicar(token, "sendPhoto", cuerpo)
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Publica contra la Bot API con un único reintento ante fallo.
     * Todo error se absorbe en silencio.
     */
    private fun publicar(token: String, metodo: String, cuerpo: RequestBody) {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$metodo")
            .post(cuerpo)
            .build()
        repeat(2) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            } catch (_: Exception) {
                // Se reintenta una vez más; si vuelve a fallar, se abandona.
            }
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
