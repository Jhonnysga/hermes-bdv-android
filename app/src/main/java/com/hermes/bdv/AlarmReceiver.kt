package com.hermes.bdv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Se dispara cuando vence una programación. Verifica que siga vigente, la
 * consume (para que no se repita tras reinicios) y abre [MainActivity] para
 * que el usuario ingrese la clave en ese momento.
 *
 * La clave bancaria nunca se guarda: la ejecución programada siempre requiere
 * que el usuario la teclee al sonar la alarma. Solo si ya hay clave en
 * memoria (caso de pruebas), MainActivity arranca la ejecución directamente.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_FECHA_HORA = "fecha_hora"
        const val EXTRA_MONTO = "monto"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fechaHora = intent.getLongExtra(EXTRA_FECHA_HORA, 0L)
        if (fechaHora <= 0L) return

        // La programación debe seguir vigente; si no, se ignora la alarma.
        val prog = Config.obtenerProgramaciones(context)
            .find { it.fechaHora == fechaHora } ?: return

        // Consumirla: no debe volver a dispararse (p. ej. tras un reinicio).
        Config.eliminarPorFechaHora(context, fechaHora)

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_EJECUTAR_PROGRAMADO, true)
            putExtra(EXTRA_FECHA_HORA, prog.fechaHora)
            putExtra(EXTRA_MONTO, prog.monto)
        }
        context.startActivity(launch)
    }
}
