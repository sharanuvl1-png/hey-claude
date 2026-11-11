package com.personal.heyclaude

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs the phone-to-Dropbox sync every 30 minutes while online. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        PhoneSync.runAll(applicationContext)
        return Result.success()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val work = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("phone-sync", ExistingPeriodicWorkPolicy.KEEP, work)
        }

        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build())
        }
    }
}

/**
 * Checks Dropbox for "fetch this file" requests every few minutes, so they arrive quickly.
 * Each run queues the next one; Android may space them out while the phone sleeps.
 */
class RequestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        try { PhoneSync.handleRequests(ctx) } catch (e: Exception) { android.util.Log.w("HeyClaude", "Request check failed", e) }
        if (DropboxClient(ctx).isLinked) queue(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "phone-requests"
        private const val EVERY_MINUTES = 3L

        /** Starts the checks unless they are already running. */
        fun start(ctx: Context) = queue(ctx, ExistingWorkPolicy.KEEP)

        private fun queue(ctx: Context, policy: ExistingWorkPolicy) {
            val work = OneTimeWorkRequestBuilder<RequestWorker>()
                .setInitialDelay(EVERY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, work)
        }
    }
}
