package com.example.minifeed.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface LocalSocialStore {
    fun load(itemId: String, fallback: LocalSocialState): LocalSocialState
    fun save(itemId: String, state: LocalSocialState)
}

class SharedPreferencesLocalSocialStore(context: Context) : LocalSocialStore {
    private val preferences = context.getSharedPreferences("local_social_state", Context.MODE_PRIVATE)

    override fun load(itemId: String, fallback: LocalSocialState): LocalSocialState {
        val raw = preferences.getString(itemId, null) ?: return fallback
        return runCatching {
            val json = JSONObject(raw)
            val commentsJson = json.optJSONArray(KEY_COMMENTS) ?: JSONArray()
            val comments = buildList {
                for (index in 0 until commentsJson.length()) {
                    val comment = commentsJson.optString(index).trim()
                    if (comment.isNotEmpty()) add(comment)
                }
            }
            LocalSocialState(
                liked = json.optBoolean(KEY_LIKED, fallback.liked),
                likeCount = json.optInt(KEY_LIKE_COUNT, fallback.likeCount).coerceAtLeast(0),
                comments = comments
            )
        }.getOrDefault(fallback)
    }

    override fun save(itemId: String, state: LocalSocialState) {
        val comments = JSONArray()
        state.comments.forEach { comments.put(it) }
        val json = JSONObject()
            .put(KEY_LIKED, state.liked)
            .put(KEY_LIKE_COUNT, state.likeCount.coerceAtLeast(0))
            .put(KEY_COMMENTS, comments)
        preferences.edit().putString(itemId, json.toString()).apply()
    }

    private companion object {
        const val KEY_LIKED = "liked"
        const val KEY_LIKE_COUNT = "likeCount"
        const val KEY_COMMENTS = "comments"
    }
}
