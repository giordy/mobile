package org.lichess.mobileV2.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Receives the widget lifecycle broadcasts and hands the actual update to
 * [BroadcastWidgetWorker], which fetches the broadcasts with WorkManager.
 */
class BroadcastWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "org.lichess.mobileV2.widgets.BROADCAST_WIDGET_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            BroadcastWidgetWorker.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BroadcastWidgetWorker.schedulePeriodicUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        BroadcastWidgetWorker.cancel(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        BroadcastWidgetWorker.refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        BroadcastWidgetWorker.refreshNow(context)
    }
}
