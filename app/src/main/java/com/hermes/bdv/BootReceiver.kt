package com.hermes.bdv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tras encender el teléfono (o reinstalar el paquete), reagenda las
 * programaciones futuras que sobrevivieron en [Config].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action
        if (accion == Intent.ACTION_BOOT_COMPLETED ||
            accion == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Scheduler.reprogramarTrasReinicio(context.applicationContext)
        }
    }
}
