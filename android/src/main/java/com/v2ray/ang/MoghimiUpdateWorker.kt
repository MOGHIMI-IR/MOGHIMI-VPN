package com.v2ray.ang

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Checks published Moghimi V2Ray releases while the app is in the background.
 * Android controls the exact execution time based on its background-work policies.
 */
class MoghimiUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        return try {
            val update = MoghimiUpdateManager.checkForUpdate(applicationContext)
            if (update != null) {
                MoghimiUpdateManager.notifyIfNew(applicationContext, update)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
