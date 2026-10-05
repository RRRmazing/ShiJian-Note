package com.shijiannote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DiaryBackground(val id: String, val name: String, val uri: String = "", val theme: String = "custom", val createdAt: Long = 0)

object DiaryBackgroundLibrary {
    const val KEY = "diary_background_library"
    val seasons = listOf(DiaryBackground("spring", "春径", theme = "spring"), DiaryBackground("summer", "夏蹊", theme = "summer"),
        DiaryBackground("autumn", "秋陌", theme = "autumn"), DiaryBackground("winter", "冬途", theme = "winter"))
    fun imported(context: Context): List<DiaryBackground> = runCatching {
        val items = JSONArray(appPreferences(context).getString(KEY, "[]"))
        (0 until items.length()).map { index -> items.getJSONObject(index).let {
            DiaryBackground(it.getString("id"), it.getString("name"), it.getString("uri"), createdAt = it.optLong("createdAt"))
        } }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())
    fun add(context: Context, block: NoteBlock): DiaryBackground {
        val next = DiaryBackground(UUID.randomUUID().toString(), block.text.ifBlank { "我的背景" }, block.uri, createdAt = System.currentTimeMillis())
        val items = JSONArray().apply { (listOf(next) + imported(context)).forEach { item ->
            put(JSONObject().put("id", item.id).put("name", item.name).put("uri", item.uri).put("createdAt", item.createdAt))
        } }
        check(appPreferences(context).edit().putString(KEY, items.toString()).commit()) { "背景库保存失败，请重试" }
        return next
    }
    fun defaultTheme(context: Context) = appPreferences(context).getString("diary_default_background_theme", "summer") ?: "summer"
    fun defaultUri(context: Context) = appPreferences(context).getString("diary_default_background_uri", "") ?: ""
    fun defaultLayout(context: Context) = appPreferences(context).getString("diary_default_road_layout", "alternate") ?: "alternate"
    fun setDefaults(context: Context, background: DiaryBackground? = null, layout: String? = null) {
        appPreferences(context).edit().apply {
            background?.let { putString("diary_default_background_theme", it.theme); putString("diary_default_background_uri", it.uri) }
            layout?.let { putString("diary_default_road_layout", it) }
        }.apply()
    }
    suspend fun bitmap(context: Context, theme: String, uri: String): Bitmap? = if (uri.isNotBlank()) loadImage(context, uri)
        else withContext(Dispatchers.IO) {
            val key = seasons.firstOrNull { it.theme == theme }?.theme ?: "summer"
            runCatching { context.assets.open("diary-backgrounds/$key.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
}
