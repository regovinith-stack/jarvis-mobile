package com.jarvis.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How "learning" works: the assistant saves short facts about you (name, habits, preferences)
 * and the last few conversation turns, and feeds them back into every request.
 * It does not retrain any model. You can view and clear it from the Memory button.
 */
class Memory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)

    companion object {
        const val MAX_FACTS = 100
        const val MAX_TURNS = 8
    }

    fun facts(): List<String> {
        val arr = JSONArray(prefs.getString("facts", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addFact(fact: String) {
        val f = fact.trim().take(200)
        if (f.isEmpty()) return
        val list = facts().toMutableList()
        if (list.any { it.equals(f, ignoreCase = true) }) return
        list.add(f)
        while (list.size > MAX_FACTS) list.removeAt(0)
        prefs.edit().putString("facts", JSONArray(list).toString()).apply()
    }

    fun forget(fragment: String): Int {
        val q = fragment.trim().lowercase()
        if (q.isEmpty()) return 0
        val list = facts().toMutableList()
        val before = list.size
        list.removeAll { it.lowercase().contains(q) }
        prefs.edit().putString("facts", JSONArray(list).toString()).apply()
        return before - list.size
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Alternating user/assistant turns, oldest first. */
    fun history(): List<Pair<String, String>> {
        val arr = JSONArray(prefs.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("role") to o.getString("text")
        }
    }

    fun addTurn(user: String, assistant: String) {
        val turns = history().toMutableList()
        turns.add("user" to user)
        turns.add("assistant" to assistant)
        while (turns.size > MAX_TURNS * 2) {
            turns.removeAt(0)
            turns.removeAt(0)
        }
        val out = JSONArray()
        turns.forEach { out.put(JSONObject().put("role", it.first).put("text", it.second)) }
        prefs.edit().putString("history", out.toString()).apply()
    }
}
