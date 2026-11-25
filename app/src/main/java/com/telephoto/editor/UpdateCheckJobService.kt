package com.telephoto.editor

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

/**
 * Periodic background JobService that checks GitHub Releases and posts
 * an Android system notification whenever a newer version of TelePhoto Editor is released.
 */
class UpdateCheckJobService : JobService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        executor.execute {
            try {
                val result = UpdateChecker.checkForUpdateSync()
                if (result is UpdateChecker.CheckResult.UpdateAvailable) {
                    UpdateChecker.maybeShowUpdateNotification(applicationContext, result.release, force = false)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
