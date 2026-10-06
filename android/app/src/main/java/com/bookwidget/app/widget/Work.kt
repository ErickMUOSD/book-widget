package com.bookwidget.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookwidget.app.data.Api
import com.bookwidget.app.data.ApiException
import com.bookwidget.app.data.Persisted
import com.bookwidget.app.data.Prefs
import com.bookwidget.app.data.WidgetState
import com.bookwidget.app.data.WidgetStore
import com.bookwidget.app.data.friendly
import java.io.IOException
import java.util.concurrent.TimeUnit

object Work {
    const val OP = "op"
    const val OP_REFRESH = "refresh"
    const val OP_ADVANCE = "advance"
    const val OP_UNSEAL = "unseal"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueue(ctx: Context, op: String, unique: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val req = OneTimeWorkRequestBuilder<WidgetWorker>()
            .setInputData(workDataOf(OP to op))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(unique, policy, req)
    }

    /** Sincroniza el widget con el servidor cada 30 min (mínimo útil; cubre cambios hechos desde la web). */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
            .setInputData(workDataOf(OP to OP_REFRESH))
            .setConstraints(network)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("widget-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelPeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("widget-refresh")
    }
}

/** Aplica un estado recibido del servidor: baja los frames, lo guarda y repinta el widget. */
object Sync {
    suspend fun apply(ctx: Context, api: Api, state: WidgetState) {
        WidgetStore.ensureFrames(ctx, api, state.sprite)
        WidgetStore.update(ctx) { Persisted(state = state, busy = false, error = null) }
        BookWidget().updateAll(ctx)
    }

    suspend fun fail(ctx: Context, message: String) {
        WidgetStore.update(ctx) { it.copy(busy = false, error = message) }
        BookWidget().updateAll(ctx)
    }
}

class WidgetWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val op = inputData.getString(Work.OP) ?: Work.OP_REFRESH
        val config = Prefs.config(ctx) ?: return Result.success()
        val api = Api(config.baseUrl, config.token)
        return try {
            when (op) {
                Work.OP_ADVANCE -> {
                    val id = WidgetStore.snapshot(ctx).state?.bookId ?: return Result.success()
                    Sync.apply(ctx, api, api.advance(id)) // genera un personaje nuevo: puede tardar
                }
                Work.OP_UNSEAL -> {
                    val id = WidgetStore.snapshot(ctx).state?.bookId ?: return Result.success()
                    api.patchState(id, sealed = false)
                }
                else -> api.widget()?.let { Sync.apply(ctx, api, it) }
            }
            Result.success()
        } catch (e: IOException) {
            when {
                op == Work.OP_ADVANCE && runAttemptCount >= 3 -> {
                    Sync.fail(ctx, e.friendly())
                    Result.failure()
                }
                op == Work.OP_REFRESH -> Result.success() // el siguiente periodo lo reintenta
                else -> Result.retry()
            }
        } catch (e: ApiException) {
            if (op == Work.OP_ADVANCE) Sync.fail(ctx, e.friendly())
            Result.failure()
        }
    }
}
