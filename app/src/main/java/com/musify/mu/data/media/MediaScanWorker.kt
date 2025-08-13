package com.musify.mu.data.media

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import com.musify.mu.data.db.DatabaseProvider
import com.musify.mu.data.repo.LibraryRepository
import java.util.concurrent.TimeUnit

class MediaScanWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "media_scan_work"
        const val TAG = "MediaScanWorker"
        const val KEY_SCAN_PROGRESS = "scan_progress"
        const val KEY_SCAN_TOTAL = "scan_total"
        const val KEY_SCAN_STATUS = "scan_status"
        
        // Schedule periodic media scanning
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val scanRequest = PeriodicWorkRequestBuilder<MediaScanWorker>(
                repeatInterval = 6, // Scan every 6 hours
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1, // Allow 1 hour flex
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing work
                scanRequest
            )
            
            Log.i(TAG, "Scheduled periodic media scanning")
        }
        
        // Schedule immediate one-time scan
        fun scanNow(context: Context): LiveData<WorkInfo> {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val scanRequest = OneTimeWorkRequestBuilder<MediaScanWorker>()
                .setConstraints(constraints)
                .addTag("immediate_scan")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "immediate_media_scan",
                ExistingWorkPolicy.REPLACE,
                scanRequest
            )
            
            return WorkManager.getInstance(context).getWorkInfoByIdLiveData(scanRequest.id)
        }
        
        // Cancel all media scanning work
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("immediate_media_scan")
            Log.i(TAG, "Cancelled all media scanning work")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting background media scan")
            
            // Set initial progress
            setProgress(workDataOf(
                KEY_SCAN_STATUS to "Starting scan...",
                KEY_SCAN_PROGRESS to 0,
                KEY_SCAN_TOTAL to 0
            ))
            
            val repository = LibraryRepository.get(context)
            var totalScanned = 0
            var totalTracks = 0
            
            // Use the Flow-based scanning for real-time progress
            repository.refreshLibraryFlow()
                .catch { e ->
                    Log.e(TAG, "Error during media scan", e)
                    throw e
                }
                .collect { progress ->
                    when (progress) {
                        is MediaStoreScanner.ScanProgress.Started -> {
                            setProgress(workDataOf(
                                KEY_SCAN_STATUS to "Scanning media library...",
                                KEY_SCAN_PROGRESS to 0,
                                KEY_SCAN_TOTAL to 0
                            ))
                        }
                        
                        is MediaStoreScanner.ScanProgress.Progress -> {
                            totalScanned = progress.scanned
                            totalTracks = progress.total
                            
                            setProgress(workDataOf(
                                KEY_SCAN_STATUS to "Scanned $totalScanned of $totalTracks tracks",
                                KEY_SCAN_PROGRESS to totalScanned,
                                KEY_SCAN_TOTAL to totalTracks
                            ))
                            
                            Log.d(TAG, "Scan progress: $totalScanned/$totalTracks")
                        }
                        
                        is MediaStoreScanner.ScanProgress.ChunkError -> {
                            Log.w(TAG, "Chunk error at offset ${progress.offset}: ${progress.error}")
                            // Continue scanning despite chunk errors
                        }
                        
                        is MediaStoreScanner.ScanProgress.Completed -> {
                            totalScanned = progress.allTracks.size
                            Log.i(TAG, "Media scan completed successfully. Found $totalScanned tracks")
                            
                            setProgress(workDataOf(
                                KEY_SCAN_STATUS to "Scan completed",
                                KEY_SCAN_PROGRESS to totalScanned,
                                KEY_SCAN_TOTAL to totalScanned
                            ))
                        }
                        
                        is MediaStoreScanner.ScanProgress.Error -> {
                            Log.e(TAG, "Fatal scan error: ${progress.message}")
                            throw Exception("Media scan failed: ${progress.message}")
                        }
                        
                        is MediaStoreScanner.ScanProgress.NoTracksFound -> {
                            Log.w(TAG, "No music tracks found on device")
                            setProgress(workDataOf(
                                KEY_SCAN_STATUS to "No music found",
                                KEY_SCAN_PROGRESS to 0,
                                KEY_SCAN_TOTAL to 0
                            ))
                        }
                    }
                }
            
            Log.i(TAG, "Background media scan completed successfully")
            Result.success(workDataOf(
                KEY_SCAN_STATUS to "Completed",
                KEY_SCAN_PROGRESS to totalScanned,
                KEY_SCAN_TOTAL to totalScanned
            ))
            
        } catch (e: Exception) {
            Log.e(TAG, "Media scan worker failed", e)
            
            if (runAttemptCount < 3) {
                Log.i(TAG, "Retrying media scan (attempt ${runAttemptCount + 1}/3)")
                Result.retry()
            } else {
                Log.e(TAG, "Media scan failed after 3 attempts")
                Result.failure(workDataOf(
                    KEY_SCAN_STATUS to "Failed: ${e.message}",
                    KEY_SCAN_PROGRESS to 0,
                    KEY_SCAN_TOTAL to 0
                ))
            }
        }
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(
            title = "Scanning Music Library",
            text = "Discovering music files on your device...",
            progress = 0,
            total = 0
        )
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
    
    private fun createNotification(
        title: String,
        text: String,
        progress: Int,
        total: Int
    ): android.app.Notification {
        val channelId = "media_scan_channel"
        
        // Create notification channel for Android O+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Media Scanning",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background media library scanning"
                setShowBadge(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        
        // Add progress bar if we have progress data
        if (total > 0) {
            builder.setProgress(total, progress, false)
            builder.setContentText("$progress of $total tracks scanned")
        } else {
            builder.setProgress(0, 0, true)
        }
        
        return builder.build()
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}