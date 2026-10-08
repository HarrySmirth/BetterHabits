package app.betterhabits.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.betterhabits.BetterHabitsApplication
import java.util.concurrent.TimeUnit

/**
 * Pushes queued changes once the device has a network, even if the app has been closed.
 * Enqueued by [SyncEngine] whenever a sync fails for a transient reason.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as BetterHabitsApplication).container.syncEngine ?: return Result.success()
        return if (engine.syncAll().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "sync"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
