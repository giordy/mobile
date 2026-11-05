package org.lichess.mobileV2.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.lichess.mobileV2.MainActivity
import org.lichess.mobileV2.R

/**
 * Fetches the top broadcasts and renders every broadcast widget.
 *
 * The work used to run directly in the [BroadcastWidgetProvider] broadcast receiver, triggered by
 * `updatePeriodMillis`. That is unreliable: the receiver only gets a few seconds before the
 * process can be killed, the system does not give the app network access while the device is
 * dozing, and a failed request replaced the widget content with the empty state, with nothing to
 * retry it. WorkManager runs the request only once the network is available, retries it with a
 * backoff, and survives reboots.
 */
class BroadcastWidgetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "BroadcastWidget"
        private const val PERIODIC_WORK_NAME = "broadcast_widget_periodic_update"
        private const val REFRESH_WORK_NAME = "broadcast_widget_refresh"
        private const val PERIODIC_INTERVAL_MINUTES = 30L
        private const val MAX_ATTEMPTS = 3
        private val THUMBNAIL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(12)

        private const val PREFS_NAME = "BroadcastWidgetPreferences"
        private const val PREF_HAS_CONTENT = "hasContent"

        private val networkConstraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Schedules the periodic update, keeping the existing schedule if there is one. */
        fun schedulePeriodicUpdate(context: Context) {
            val request = PeriodicWorkRequestBuilder<BroadcastWidgetWorker>(
                PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Refreshes the widgets as soon as the network is available. */
        fun refreshNow(context: Context) {
            // Always keep the periodic work enqueued before enqueuing a one-time request: when
            // WorkManager goes from no pending work to some, it toggles a component of the app,
            // which makes the system send APPWIDGET_UPDATE again and would loop forever.
            schedulePeriodicUpdate(context)
            val request = OneTimeWorkRequestBuilder<BroadcastWidgetWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                REFRESH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Cancels all the work once the last widget has been removed. */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(REFRESH_WORK_NAME)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(PREF_HAS_CONTENT)
                .apply()
        }
    }

    override fun doWork(): Result {
        val context = applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, BroadcastWidgetProvider::class.java)
        )
        if (appWidgetIds.isEmpty()) return Result.success()

        val homePrefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        val lichessHost = homePrefs.getString("lichessHost", "lichess.org") ?: "lichess.org"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val broadcasts = try {
            fetchBroadcasts(lichessHost)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching broadcasts (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
            // Keep showing the last broadcasts we got rather than replacing them with an error;
            // the next periodic update will try again. Only a widget that never got any content
            // shows the error, so it is not left blank.
            if (!prefs.getBoolean(PREF_HAS_CONTENT, false) && !isStopped) {
                appWidgetIds.forEach { renderError(context, appWidgetManager, it) }
            }
            // Not a failure as far as WorkManager is concerned, so that the retry count is reset
            // and the periodic work simply runs again at its next interval.
            return Result.success()
        }

        if (isStopped) return Result.success()

        appWidgetIds.forEach { appWidgetId ->
            try {
                renderBroadcasts(context, appWidgetManager, appWidgetId, broadcasts, lichessHost)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $appWidgetId", e)
            }
        }
        prefs.edit().putBoolean(PREF_HAS_CONTENT, true).apply()
        return Result.success()
    }

    private fun createBaseViews(context: Context, appWidgetId: Int): RemoteViews {
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_broadcast)

        val homeIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val homePendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            homeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        remoteViews.setOnClickPendingIntent(R.id.widget_header, homePendingIntent)

        // Tapping the empty or error message refreshes the widget.
        val refreshIntent = Intent(context, BroadcastWidgetProvider::class.java).apply {
            action = BroadcastWidgetProvider.ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        remoteViews.setOnClickPendingIntent(R.id.empty_view, refreshPendingIntent)

        return remoteViews
    }

    private fun renderError(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val remoteViews = createBaseViews(context, appWidgetId)
        remoteViews.removeAllViews(R.id.broadcast_list)
        remoteViews.setViewVisibility(R.id.broadcast_list, View.GONE)
        remoteViews.setTextViewText(R.id.empty_view, context.getString(R.string.widget_broadcasts_error))
        remoteViews.setViewVisibility(R.id.empty_view, View.VISIBLE)
        appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }

    private fun renderBroadcasts(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        broadcasts: List<BroadcastItem>,
        lichessHost: String
    ) {
        val remoteViews = createBaseViews(context, appWidgetId)
        remoteViews.removeAllViews(R.id.broadcast_list)

        if (broadcasts.isEmpty()) {
            remoteViews.setTextViewText(R.id.empty_view, context.getString(R.string.widget_no_broadcasts))
            remoteViews.setViewVisibility(R.id.empty_view, View.VISIBLE)
            remoteViews.setViewVisibility(R.id.broadcast_list, View.GONE)
        } else {
            remoteViews.setViewVisibility(R.id.empty_view, View.GONE)
            remoteViews.setViewVisibility(R.id.broadcast_list, View.VISIBLE)

            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            val availableHeight = if (isPortrait && maxHeight > 0) maxHeight else minHeight

            val numRows = if (availableHeight == 0) 3 else ((availableHeight - 44) / 60).coerceIn(1, 4)
            val showThumbnails = minWidth == 0 || minWidth >= 200

            val displayItems = broadcasts.take(numRows)

            // Fetch all thumbnails concurrently, mirroring the iOS withTaskGroup approach.
            // Each item gets its own thread so all network requests run in parallel;
            // total wait time ≈ slowest single request instead of sum of all requests.
            val sizePx = (48 * context.resources.displayMetrics.density).toInt()
            val bitmaps: List<Bitmap?> = if (showThumbnails) {
                val executor = Executors.newFixedThreadPool(displayItems.size.coerceAtLeast(1))
                try {
                    val futures = displayItems.map { item ->
                        executor.submit(Callable<Bitmap?> {
                            item.imageUrl?.let { fetchRoundBitmap(it, sizePx) }
                        })
                    }
                    val deadline = System.nanoTime() + THUMBNAIL_TIMEOUT_NANOS
                    futures.map { future ->
                        try {
                            val remaining = deadline - System.nanoTime()
                            if (remaining <= 0) throw TimeoutException("Thumbnail deadline exceeded")
                            future.get(remaining, TimeUnit.NANOSECONDS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching thumbnail", e)
                            future.cancel(true)
                            null
                        }
                    }
                } finally {
                    executor.shutdownNow()
                }
            } else {
                List(displayItems.size) { null }
            }

            displayItems.forEachIndexed { index, item ->
                val itemViews = RemoteViews(context.packageName, R.layout.widget_broadcast_item)
                setupItemViews(context, itemViews, item, index, showThumbnails, bitmaps[index], lichessHost, appWidgetId)
                remoteViews.addView(R.id.broadcast_list, itemViews)
            }
        }
        appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }

    private fun setupItemViews(
        context: Context,
        itemViews: RemoteViews,
        item: BroadcastItem,
        index: Int,
        showThumbnails: Boolean,
        bitmap: Bitmap?,  // Pre-fetched concurrently; null when thumbnails are disabled or fetch failed
        lichessHost: String,
        appWidgetId: Int
    ) {
        itemViews.setTextViewText(R.id.item_title, item.title)

        if (item.isLive) {
            itemViews.setViewVisibility(R.id.item_live_indicator, View.VISIBLE)
            itemViews.setTextViewText(R.id.item_status, "${context.getString(R.string.widget_live)} · ${item.roundName}")
            itemViews.setTextColor(R.id.item_status, context.getColor(R.color.widget_live_indicator))
        } else {
            itemViews.setViewVisibility(R.id.item_live_indicator, View.GONE)
            val statusText = if (item.startsAt > 0) {
                val dateStr = DateUtils.formatDateTime(
                    context,
                    item.startsAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
                )
                "${item.roundName} · $dateStr"
            } else {
                item.roundName
            }
            itemViews.setTextViewText(R.id.item_status, statusText)
            itemViews.setTextColor(R.id.item_status, context.getColor(R.color.widget_text_secondary))
        }

        if (showThumbnails) {
            itemViews.setViewVisibility(R.id.item_thumbnail_container, View.VISIBLE)
            if (bitmap != null) {
                itemViews.setImageViewBitmap(R.id.item_thumbnail, bitmap)
            } else {
                setFallbackThumbnail(context, itemViews)
            }
        } else {
            itemViews.setViewVisibility(R.id.item_thumbnail_container, View.GONE)
        }

        val scheme = if (lichessHost.startsWith("localhost")) "http" else "https"
        val clickUri = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(lichessHost)
            .appendPath("broadcast")
            .appendPath(item.tourSlug)
            .appendPath(item.roundSlug)
            .appendPath(item.id)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, clickUri).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val requestCode = appWidgetId * 10 + index
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        itemViews.setOnClickPendingIntent(R.id.item_container, pendingIntent)
    }

    private fun setFallbackThumbnail(context: Context, itemViews: RemoteViews) {
        itemViews.setImageViewResource(R.id.item_thumbnail, R.drawable.ic_stat_lichess_notification)
        val pad = (12 * context.resources.displayMetrics.density).toInt()
        itemViews.setViewPadding(R.id.item_thumbnail, pad, pad, pad, pad)
        itemViews.setInt(R.id.item_thumbnail, "setColorFilter", context.getColor(R.color.widget_text_secondary))
    }

    /** Fetches the active broadcasts. Throws on any network or parsing error. */
    private fun fetchBroadcasts(lichessHost: String): List<BroadcastItem> {
        val scheme = if (lichessHost.startsWith("localhost")) "http" else "https"
        val url = URL("$scheme://$lichessHost/api/broadcast/top")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected response code $responseCode")
            }
            return connection.inputStream.use { stream ->
                val jsonString = stream.bufferedReader().use { it.readText() }
                val active = JSONObject(jsonString).getJSONArray("active")

                (0 until active.length()).map { i ->
                    val item = active.getJSONObject(i)
                    val tour = item.getJSONObject("tour")
                    val round = item.getJSONObject("round")
                    val id = item.optJSONObject("roundToLink")?.optString("id")?.takeIf { it.isNotBlank() } ?: round.getString("id")
                    val title = item.optString("group").takeIf { !it.isNullOrBlank() } ?: tour.getString("name")

                    BroadcastItem(
                        id = id,
                        title = title,
                        roundName = round.getString("name"),
                        tourSlug = tour.getString("slug"),
                        roundSlug = round.getString("slug"),
                        isLive = round.optBoolean("ongoing", false),
                        startsAt = round.optLong("startsAt", 0),
                        imageUrl = tour.optString("image").takeIf { !it.isNullOrBlank() }
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchRoundBitmap(urlStr: String, sizePx: Int): Bitmap? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            try {
                connection.inputStream.use { stream ->
                    val src = BitmapFactory.decodeStream(stream) ?: return null
                    val square = cropToSquare(src, sizePx)
                    val rounded = getRoundedCornerBitmap(square, (sizePx * 0.12f).toInt())
                    if (square != src) square.recycle()
                    if (src != rounded) src.recycle()
                    rounded
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch thumbnail: $urlStr", e)
            null
        }
    }

    private fun cropToSquare(src: Bitmap, targetSize: Int): Bitmap {
        val size = minOf(src.width, src.height)
        val cropX = (src.width - size) / 2
        val cropY = (src.height - size) / 2
        val cropped = Bitmap.createBitmap(src, cropX, cropY, size, size)
        return if (size == targetSize) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true).also {
                if (cropped != src) cropped.recycle()
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, pixels: Int): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)
        val roundPx = pixels.toFloat()

        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.BLACK
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }

    data class BroadcastItem(
        val id: String,
        val title: String,
        val roundName: String,
        val tourSlug: String,
        val roundSlug: String,
        val isLive: Boolean,
        val startsAt: Long,
        val imageUrl: String?
    )
}
