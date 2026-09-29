package com.hermes.bdv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Una ejecución programada. [fechaHora] en milisegundos (epoch).
 */
data class Programacion(val fechaHora: Long, val monto: String)

/**
 * Configuración persistente de Hermes (SharedPreferences "hermes_cfg").
 *
 * NOTA DE SEGURIDAD: la clave bancaria NUNCA se guarda aquí ni en ningún
 * archivo. Solo existe en memoria ([MainActivity.claveEnMemoria]) mientras
 * dura una ejecución y se limpia al terminar.
 */
object Config {
    private const val PREFS = "hermes_cfg"
    private const val K_MONTO = "monto"
    private const val K_HORA_OBJETIVO = "hora_objetivo"
    private const val K_TOKEN_TG = "token_telegram"
    private const val K_CHAT_TG = "chat_telegram"
    private const val K_AVISOS = "avisos_activos"
    private const val K_PROGS = "programaciones"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMonto(c: Context): String =
        prefs(c).getString(K_MONTO, "500") ?: "500"

    fun setMonto(c: Context, v: String) {
        prefs(c).edit().putString(K_MONTO, v.trim()).apply()
    }

    fun getHoraObjetivo(c: Context): String =
        prefs(c).getString(K_HORA_OBJETIVO, "08:00:00") ?: "08:00:00"

    fun setHoraObjetivo(c: Context, v: String) {
        prefs(c).edit().putString(K_HORA_OBJETIVO, v.trim()).apply()
    }

    fun getTokenTelegram(c: Context): String =
        prefs(c).getString(K_TOKEN_TG, "") ?: ""

    fun setTokenTelegram(c: Context, v: String) {
        prefs(c).edit().putString(K_TOKEN_TG, v.trim()).apply()
    }

    fun getChatTelegram(c: Context): Long =
        prefs(c).getLong(K_CHAT_TG, 0L)

    fun setChatTelegram(c: Context, v: Long) {
        prefs(c).edit().putLong(K_CHAT_TG, v).apply()
    }

    fun isAvisosActivos(c: Context): Boolean =
        prefs(c).getBoolean(K_AVISOS, true)

    fun setAvisosActivos(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_AVISOS, v).apply()
    }

    /** Programaciones ordenadas por fecha/hora ascendente. */
    fun obtenerProgramaciones(c: Context): List<Programacion> {
        val raw = prefs(c).getString(K_PROGS, "[]") ?: "[]"
        val out = mutableListOf<Programacion>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                out.add(Programacion(o.getLong("fh"), o.getString("monto")))
            }
        } catch (_: Exception) {
            // JSON corrupto: se trata como lista vacía
        }
        return out.sortedBy { it.fechaHora }
    }

    private fun guardarProgramaciones(c: Context, lista: List<Programacion>) {
        val arr = JSONArray()
        for (p in lista.sortedBy { it.fechaHora }) {
            arr.put(JSONObject().put("fh", p.fechaHora).put("monto", p.monto))
        }
        prefs(c).edit().putString(K_PROGS, arr.toString()).apply()
    }

    fun agregarProgramacion(c: Context, fechaHora: Long, monto: String) {
        val lista = obtenerProgramaciones(c).toMutableList()
        if (lista.none { it.fechaHora == fechaHora }) {
            lista.add(Programacion(fechaHora, monto))
        }
        guardarProgramaciones(c, lista)
    }

    /** Reemplaza la lista completa (usado tras reinicio para descartar vencidas). */
    fun reemplazarProgramaciones(c: Context, lista: List<Programacion>) {
        guardarProgramaciones(c, lista)
    }

    /** Elimina por índice sobre la lista ordenada. */
    fun eliminarProgramacion(c: Context, index: Int) {
        val lista = obtenerProgramaciones(c).toMutableList()
        if (index in lista.indices) {
            lista.removeAt(index)
            guardarProgramaciones(c, lista)
        }
    }

    fun eliminarPorFechaHora(c: Context, fechaHora: Long) {
        guardarProgramaciones(
            c,
            obtenerProgramaciones(c).filter { it.fechaHora != fechaHora }
        )
    }

    fun limpiarProgramaciones(c: Context) {
        prefs(c).edit().remove(K_PROGS).apply()
    }
}
