package com.hcwebhook.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val syncManager = SyncManager(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val syncResult = syncManager.performSync()
            when {
                syncResult.isSuccess -> {
                    Log.d(TAG, "Sync completed successfully")
                    Result.success()
                }
                syncResult.isFailure -> {
                    Log.e(TAG, "Sync failed: ${syncResult.exceptionOrNull()?.message}")
                    Result.failure()
                }
                else -> {
                    Log.d(TAG, "Sync completed with no new data")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker encountered an error", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}