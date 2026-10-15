package com.jarvis.agent

import android.content.Context

/** Stores the API key in app-private storage. Fine for personal use; for anything you
 *  distribute, put the key on a server you control and call that instead. */
object AppSettings {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = prefs(c).getString("api_key", "").orEmpty()
    fun setApiKey(c: Context, key: String) {
        prefs(c).edit().putString("api_key", key.trim()).apply()
    }
}
