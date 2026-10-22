package com.bookwidget.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import com.bookwidget.app.data.WidgetStore

/** «Romper sello»: se revela al instante (local) y se avisa al servidor en segundo plano. */
class BreakSealAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStore.update(context) { p -> p.copy(state = p.state?.copy(sealed = false), error = null) }
        BookWidget().updateAll(context)
        Work.enqueue(context, Work.OP_UNSEAL, unique = "unseal", requireNetwork = false)
    }
}

/** «Terminé ✓»: capítulo + 1 sin abrir la app. Muestra «PROCESANDO…» hasta que llega el nuevo personaje. */
class AdvanceChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStore.update(context) { it.copy(busy = true, error = null) }
        BookWidget().updateAll(context)
        // KEEP: un doble toque no avanza dos capítulos · sin constraint de red: falla rápido con error visible
        Work.enqueue(context, Work.OP_ADVANCE, unique = "advance", policy = ExistingWorkPolicy.KEEP, requireNetwork = false)
    }
}

/** Niebla · Pista · Peligro: resalta al instante el nivel elegido y pide al servidor la leyenda de ese nivel. */
class SetLevelAction : ActionCallback {
    companion object {
        val LEVEL = ActionParameters.Key<String>("level")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val level = parameters[LEVEL] ?: return
        WidgetStore.update(context) { p -> p.copy(state = p.state?.copy(spoilerLevel = level), error = null) }
        BookWidget().updateAll(context)
        // REPLACE: si se toca otro nivel enseguida, gana el último (el store ya guarda el elegido).
        Work.enqueue(context, Work.OP_SETLEVEL, unique = "setlevel", requireNetwork = false)
    }
}
