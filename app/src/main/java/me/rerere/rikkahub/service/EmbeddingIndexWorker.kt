package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.embedding.SemanticIndexManager
import java.util.concurrent.TimeUnit

class EmbeddingIndexWorker(
    appContext: Context,
    params: WorkerParameters,
    private val semanticIndexManager: SemanticIndexManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            semanticIndexManager.indexPending()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "embedding-index"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
