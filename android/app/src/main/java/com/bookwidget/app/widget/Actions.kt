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
        WidgetStore.update(context) { p -> p.copy(state = p.state?.copy(sealed = false)) }
        BookWidget().updateAll(context)
        Work.enqueue(context, Work.OP_UNSEAL, unique = "unseal")
    }
}

/** «Terminé ✓»: capítulo + 1 sin abrir la app. Muestra «invocando…» hasta que llega el nuevo personaje. */
class AdvanceChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStore.update(context) { it.copy(busy = true, error = null) }
        BookWidget().updateAll(context)
        // KEEP: un doble toque no avanza dos capítulos
        Work.enqueue(context, Work.OP_ADVANCE, unique = "advance", policy = ExistingWorkPolicy.KEEP)
    }
}
