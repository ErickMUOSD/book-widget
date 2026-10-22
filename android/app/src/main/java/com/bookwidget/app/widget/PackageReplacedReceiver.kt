package com.bookwidget.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tras actualizar la app, Android deja el widget en «cargando» hasta que algo lo repinta.
 * Al recibir ACTION_MY_PACKAGE_REPLACED lo repintamos desde la caché local (sin red).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Work.refreshWidget(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
