package com.bookwidget.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookwidget.app.data.Generator
import com.bookwidget.app.data.Library
import java.io.IOException
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object Work {
    private const val BOOK = "book"

    /** Prepara en segundo plano lo que el lector necesita (sobrevive a cerrar la app). Sin requisito de red:
     *  citas y dibujos son locales; si no hay conexión, Groq se reintenta en la siguiente pasada. */
    fun generate(ctx: Context, bookId: String) {
        val req = OneTimeWorkRequestBuilder<GenerateWorker>()
            .setInputData(workDataOf(BOOK to bookId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: si ya está generando, esta pasada vuelve a mirar al terminar (p. ej. tras «Terminé ✓»)
        WorkManager.getInstance(ctx).enqueueUniqueWork("gen-$bookId", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Avisa del cambio (el widget vivo lo recoge) y fuerza el repintado por si la sesión del widget ya no existe. */
    suspend fun refreshWidget(ctx: Context) {
        Library.notifyChanged()
        BookWidget().updateAll(ctx)
    }
}

class GenerateWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("book") ?: return Result.success()
        return try {
            Generator.run(ctx, id) { Work.refreshWidget(ctx) }
            Work.refreshWidget(ctx)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount >= 4) {
                Library.update(ctx, id) { it.copy(error = "No se pudo generar: ${e.message}".take(200)) }
                Work.refreshWidget(ctx)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/** Tras actualizar la app, Android deja el widget en «cargando» hasta que algo lo repinta: se repinta aquí. */
class PackageReplacedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                Work.refreshWidget(context)
            } finally {
                pending.finish()
            }
        }
    }
}
