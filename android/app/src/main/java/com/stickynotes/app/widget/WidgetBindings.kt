package com.stickynotes.app.widget

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which note each home-screen widget shows (appWidgetId -> noteId). Local to the phone. */
object WidgetBindings {
    private const val PREFS = "widget_bindings"
    private const val PENDING = "pending_note"
    private const val PENDING_AT = "pending_at"
    private val _map = MutableStateFlow<Map<Int, String>>(emptyMap())
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun flow(ctx: Context): StateFlow<Map<Int, String>> {
        ensure(ctx)
        return _map
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (loaded) return
        _map.value = prefs(ctx).all.mapNotNull { (k, v) ->
            k.removePrefix("w").toIntOrNull()?.let { id -> (v as? String)?.let { id to it } }
        }.toMap()
        loaded = true
    }

    fun noteFor(ctx: Context, appWidgetId: Int): String? = flow(ctx).value[appWidgetId]

    @Synchronized
    fun bind(ctx: Context, appWidgetId: Int, noteId: String) {
        ensure(ctx)
        prefs(ctx).edit().putString("w$appWidgetId", noteId).apply()
        _map.value = _map.value + (appWidgetId to noteId)
    }

    @Synchronized
    fun unbind(ctx: Context, appWidgetId: Int) {
        ensure(ctx)
        prefs(ctx).edit().remove("w$appWidgetId").apply()
        _map.value = _map.value - appWidgetId
    }

    /** A torn-off note waiting for its new widget to be placed. */
    fun setPending(ctx: Context, noteId: String) {
        ctx.getSharedPreferences(PREFS + "_pending", Context.MODE_PRIVATE).edit()
            .putString(PENDING, noteId).putLong(PENDING_AT, System.currentTimeMillis()).apply()
    }

    fun clearPending(ctx: Context) {
        ctx.getSharedPreferences(PREFS + "_pending", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** If this widget has no note yet and a tear-off happened in the last 2 minutes, adopt it. */
    fun claimPendingIfUnbound(ctx: Context, appWidgetId: Int) {
        if (noteFor(ctx, appWidgetId) != null) return
        val p = ctx.getSharedPreferences(PREFS + "_pending", Context.MODE_PRIVATE)
        val id = p.getString(PENDING, null) ?: return
        if (System.currentTimeMillis() - p.getLong(PENDING_AT, 0) > 120_000) return
        bind(ctx, appWidgetId, id)
        clearPending(ctx)
    }
}
