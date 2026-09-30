package com.hermes.bdv

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Control de Hermes desde Telegram (petición del usuario 29/09/2026).
 *
 * Hace polling a getUpdates del Bot API y procesa comandos SOLO del
 * chat autorizado ([Config.getChatTelegram]):
 *
 *   /ejecutar [monto]  – inicia una ejecución (usa el monto guardado si no se indica)
 *   /detener           – detiene la ejecución en curso
 *   /estado            – reporta si el servicio está activo y si hay ejecución en curso
 *   /monto <valor>     – cambia el monto preconfigurado (1–500)
 *   /ayuda             – lista los comandos
 *
 * El polling corre en un hilo de fondo mientras la app está en ejecución.
 * Los comandos nunca incluyen la clave: el servicio la lee del almacén cifrado.
 */
object TelegramControl {
    private const val TAG = "HermesTgCtl"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var ultimoUpdateId: Long = 0L

    /** Inicia el polling si hay token y chat configurados. */
    fun iniciar(context: Context) {
        if (job?.isActive == true) return
        val token = Config.getTokenTelegram(context)
        val chatId = Config.getChatTelegram(context)
        if (token.isBlank() || chatId == 0L) {
            Log.i(TAG, "Telegram sin configurar: control desactivado")
            return
        }
        val appContext = context.applicationContext
        job = scope.launch {
            Log.i(TAG, "control por Telegram iniciado")
            while (isActive) {
                try {
                    procesarUpdates(appContext, token, chatId)
                } catch (e: Exception) {
                    Log.w(TAG, "error en polling Telegram", e)
                }
                delay(3_000)
            }
        }
    }

    fun detener() {
        job?.cancel()
        job = null
    }

    private fun procesarUpdates(context: Context, token: String, chatIdAutorizado: Long) {
        val url = "https://api.telegram.org/bot$token/getUpdates" +
            "?timeout=25&offset=${ultimoUpdateId + 1}&allowed_updates=[\"message\"]"
        val resp = client.newCall(Request.Builder().url(url).get().build())
            .execute()
        val cuerpo = resp.body?.string().orEmpty()
        resp.close()
        if (cuerpo.isBlank()) return
        val json: JSONObject
        try {
            json = JSONObject(cuerpo)
        } catch (_: Exception) { return }
        if (!json.optBoolean("ok", false)) return
        val arr = json.optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val upd = arr.optJSONObject(i) ?: continue
            val updateId = upd.optLong("update_id", 0L)
            if (updateId > ultimoUpdateId) ultimoUpdateId = updateId
            val msg = upd.optJSONObject("message") ?: continue
            val chat = msg.optJSONObject("chat") ?: continue
            val chatId = chat.optLong("id", 0L)
            // Solo el chat autorizado puede controlar
            if (chatId != chatIdAutorizado) continue
            val texto = msg.optString("text", "").trim()
            if (texto.isEmpty()) continue
            manejarComando(context, token, chatId, texto)
        }
    }

    private fun manejarComando(context: Context, token: String, chatId: Long, texto: String) {
        val partes = texto.split("\\s+".toRegex(), limit = 2)
        val cmd = partes[0].lowercase().substringBefore("@")
        val arg = partes.getOrNull(1)?.trim().orEmpty()
        Log.i(TAG, "comando: $cmd")
        when (cmd) {
            "/ejecutar", "/ejecutar_manual" -> {
                val monto = arg.ifEmpty { Config.getMonto(context) }
                if (!LogicaPura.validarMonto(monto)) {
                    TelegramNotifier.enviarTexto(token, chatId, "Monto inválido (1–500 USD).")
                    return
                }
                if (!ClaveSegura.configurada(context)) {
                    TelegramNotifier.enviarTexto(
                        token, chatId,
                        "Clave sin configurar. Configúrala en la app antes de ejecutar."
                    )
                    return
                }
                val ok = HermesAccessibilityService.iniciar(context, monto, "", "ahora")
                TelegramNotifier.enviarTexto(
                    token, chatId,
                    if (ok) "▶ Ejecución iniciada ($monto USD)." else "No se pudo iniciar el servicio."
                )
            }
            "/detener", "/stop" -> {
                context.sendBroadcast(Intent(MainActivity.ACCION_DETENER).apply {
                    setPackage(context.packageName)
                })
                TelegramNotifier.enviarTexto(token, chatId, "⏹ Detener enviado.")
            }
            "/estado", "/status" -> {
                val servicioOn = HermesAccessibilityService.estaHabilitado(context)
                val claveOk = ClaveSegura.configurada(context)
                val monto = Config.getMonto(context)
                TelegramNotifier.enviarTexto(
                    token, chatId,
                    "Hermes:\n" +
                        "· Servicio accesibilidad: ${if (servicioOn) "activo" else "inactivo"}\n" +
                        "· Clave: ${if (claveOk) "configurada" else "sin configurar"}\n" +
                        "· Monto: $monto USD"
                )
            }
            "/monto" -> {
                if (!LogicaPura.validarMonto(arg)) {
                    TelegramNotifier.enviarTexto(token, chatId, "Uso: /monto <1–500>")
                } else {
                    Config.setMonto(context, arg)
                    TelegramNotifier.enviarTexto(token, chatId, "Monto actualizado: $arg USD.")
                }
            }
            "/ayuda", "/help", "/start" -> {
                TelegramNotifier.enviarTexto(
                    token, chatId,
                    "Comandos Hermes:\n" +
                        "/ejecutar [monto] – iniciar\n" +
                        "/detener – detener\n" +
                        "/estado – estado\n" +
                        "/monto <1–500> – cambiar monto\n" +
                        "/ayuda – esta ayuda"
                )
            }
        }
    }
}
