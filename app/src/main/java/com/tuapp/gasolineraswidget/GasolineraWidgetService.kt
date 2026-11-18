package com.tuapp.gasolineraswidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Servicio que proporciona las filas del ListView al launcher.
 */
class GasolineraWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory {
        var appWidgetId = intent.getIntExtra(
            EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId = intent.getIntExtra(
                EXTRA_REMOTE_ADAPTER_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId = intent.data?.lastPathSegment?.toIntOrNull()
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        }

        return GasolineraRemoteViewsFactory(applicationContext, appWidgetId)
    }

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"
        private const val EXTRA_REMOTE_ADAPTER_ID = "remoteAdapterAppWidgetId"
    }
}
