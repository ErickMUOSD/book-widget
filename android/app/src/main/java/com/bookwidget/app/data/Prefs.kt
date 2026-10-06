package com.bookwidget.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.store by preferencesDataStore("settings")

data class Config(val baseUrl: String, val token: String)

object Prefs {
    private val URL = stringPreferencesKey("base_url")
    private val TOKEN = stringPreferencesKey("token")
    private val ANIMATE = booleanPreferencesKey("animate")

    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    suspend fun config(ctx: Context): Config? {
        val p = ctx.store.data.first()
        val url = p[URL].orEmpty()
        val token = p[TOKEN].orEmpty()
        return if (url.isNotEmpty() && token.isNotEmpty()) Config(url, token) else null
    }

    suspend fun saveConfig(ctx: Context, url: String, token: String) {
        ctx.store.edit {
            it[URL] = normalizeUrl(url)
            it[TOKEN] = token.trim()
        }
    }

    suspend fun clearConfig(ctx: Context) {
        ctx.store.edit {
            it.remove(URL)
            it.remove(TOKEN)
        }
    }

    suspend fun animate(ctx: Context): Boolean = ctx.store.data.first()[ANIMATE] ?: true

    suspend fun setAnimate(ctx: Context, value: Boolean) {
        ctx.store.edit { it[ANIMATE] = value }
    }
}
