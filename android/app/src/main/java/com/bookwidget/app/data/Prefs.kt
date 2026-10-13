package com.bookwidget.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookwidget.app.BuildConfig
import kotlinx.coroutines.flow.first

private val Context.store by preferencesDataStore("settings")

object Prefs {
    private val GROQ_KEY = stringPreferencesKey("narrator_key_groq") // mismo nombre que antes: se conserva la clave
    private val ACTIVE = stringPreferencesKey("active_book")
    private val ANIMATE = booleanPreferencesKey("animate")

    /** Clave de Groq del usuario o, si no hay, la de local.properties (solo builds de prueba). */
    suspend fun groqKey(ctx: Context): String =
        ctx.store.data.first()[GROQ_KEY]?.takeIf { it.isNotBlank() } ?: BuildConfig.GROQ_API_KEY

    suspend fun setGroqKey(ctx: Context, key: String) {
        ctx.store.edit { it[GROQ_KEY] = key.trim() }
    }

    suspend fun activeBook(ctx: Context): String? = ctx.store.data.first()[ACTIVE]

    suspend fun setActiveBook(ctx: Context, id: String?) {
        ctx.store.edit { if (id == null) it.remove(ACTIVE) else it[ACTIVE] = id }
    }

    suspend fun animate(ctx: Context): Boolean = ctx.store.data.first()[ANIMATE] ?: true

    suspend fun setAnimate(ctx: Context, value: Boolean) {
        ctx.store.edit { it[ANIMATE] = value }
    }
}
