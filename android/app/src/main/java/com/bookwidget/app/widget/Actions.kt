package com.bookwidget.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.bookwidget.app.data.Library
import com.bookwidget.app.data.Prefs
import com.bookwidget.app.data.Snapshot
import com.bookwidget.app.data.WidgetStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado que pinta el widget, atómico: el dibujo (snapshot) y si hay un toque procesándose.
 * Una acción pone «procesando», hace el cambio y publica el estado nuevo y el fin del proceso de una vez:
 * así el orden es siempre toque → indicador → estado nuevo, sin parpadeos.
 */
object WidgetFeedback {
    data class Ui(val snap: Snapshot?, val busy: Boolean)

    val ui = MutableStateFlow(Ui(null, false))

    suspend fun run(context: Context, block: suspend () -> Unit) {
        ui.update { it.copy(busy = true) }
        try {
            block()
        } finally {
            ui.value = Ui(WidgetStore.snapshot(context), busy = false)
        }
    }

    /** Cambios que no vienen del widget (app, generador): se publica el dibujo sin tocar «procesando». */
    suspend fun reload(context: Context) {
        val snap = WidgetStore.snapshot(context)
        ui.update { it.copy(snap = snap) }
    }
}

/** «Romper sello»: todo es local, se revela al instante. */
class BreakSealAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = Prefs.activeBook(context) ?: return
        WidgetFeedback.run(context) {
            Library.setSealed(context, id, false)
            Work.refreshWidget(context)
        }
    }
}

/** «Terminé ✓»: capítulo + 1 sin abrir la app; si el siguiente no estaba preparado, se genera ahora. */
class AdvanceChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = Prefs.activeBook(context) ?: return
        WidgetFeedback.run(context) {
            Library.advance(context, id)
            Work.refreshWidget(context)
        }
        Work.generate(context, id)
    }
}

/** Niebla / Pista / Peligro desde el widget (local, instantáneo). */
class SetLevelAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = Prefs.activeBook(context) ?: return
        val level = parameters[LEVEL] ?: return
        WidgetFeedback.run(context) {
            Library.setLevel(context, id, level)
            Work.refreshWidget(context)
        }
    }

    companion object {
        val LEVEL = ActionParameters.Key<String>("level")
    }
}
