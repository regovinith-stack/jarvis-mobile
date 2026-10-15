package com.jarvis.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Sends what you said (plus remembered facts and recent turns) to the Claude API and
 *  gets back speech to say and a list of device actions to run. */
object Brain {
    private const val MODEL = "claude-sonnet-5-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private val JSON_TYPE = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result(
        val speech: String,
        val actions: List<JSONObject>,
        val remember: List<String>
    )

    suspend fun think(context: Context, userText: String): Result = withContext(Dispatchers.IO) {
        val key = AppSettings.apiKey(context)
        if (key.isBlank()) throw IllegalStateException("No API key set. Tap \"API key\" and paste yours.")

        val memory = Memory(context)
        val messages = JSONArray()
        memory.history().forEach { (role, text) ->
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val system = buildSystemPrompt(memory.facts())

        // Try with web search first (for current information); retry without it if the account
        // or request doesn't allow the tool.
        var (code, body) = call(key, system, messages, useSearch = true)
        if (code !in 200..299 && code != 401 && code != 429) {
            val retry = call(key, system, messages, useSearch = false)
            code = retry.first
            body = retry.second
        }
        if (code == 401) throw IllegalStateException("The API key was rejected. Set it again with the API key button.")
        if (code == 429) throw IllegalStateException("Rate limited. Try again in a moment.")
        if (code !in 200..299) {
            val msg = try {
                JSONObject(body).optJSONObject("error")?.optString("message")
            } catch (e: Exception) { null }
            throw IllegalStateException("API error $code${if (msg.isNullOrBlank()) "" else ": $msg"}")
        }

        // With web search the reply can be split across several text blocks, so join them all.
        val content = JSONObject(body).optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val raw = sb.toString().trim()
        val obj = extractJson(raw)

        val speech = obj?.optString("speech").orEmpty().ifBlank { if (obj == null) raw.take(400) else "" }
        val actions = obj?.optJSONArray("actions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.take(6)
        } ?: emptyList()
        val remember = obj?.optJSONArray("remember")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()

        memory.addTurn(userText, obj?.toString() ?: raw)
        Result(speech, actions, remember)
    }

    private fun call(key: String, system: String, messages: JSONArray, useSearch: Boolean): Pair<Int, String> {
        val payload = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", messages)
        if (useSearch) {
            payload.put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 3)
                )
            )
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        return client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (e: Exception) { null }
    }

    private fun buildSystemPrompt(facts: List<String>): String {
        val known = if (facts.isEmpty()) "Nothing yet." else facts.joinToString("\n") { "- $it" }
        return """
You are Jarvis, a voice assistant running on the user's Android phone. Reply with ONLY a JSON object (no markdown, no code fences) in this shape:
{"speech": "<what to say aloud>", "actions": [ ... ], "remember": ["<durable fact about the user>"]}

Available actions (each an object with a "type"):
- {"type":"open_app","name":"<app name>"}
- {"type":"open_url","url":"https://..."}
- {"type":"web_search","query":"..."}  (opens a Google search on the phone)
- {"type":"dial","number":"+911234567890"}  (opens the dialer; the user presses call)
- {"type":"sms","number":"...","body":"..."}  (opens the composer; the user presses send)
- {"type":"alarm","hour":7,"minute":30,"label":"..."}
- {"type":"timer","seconds":300,"label":"..."}
- {"type":"navigate","query":"place or address"}
- {"type":"flashlight","on":true}
- {"type":"volume","direction":"up|down|mute|max"} or {"type":"volume","level":0-100}
- {"type":"settings","page":"wifi|bluetooth|display|sound|location|battery|main"}
- {"type":"system","do":"home|back|recents|notifications|quick_settings|lock_screen|screenshot"}
- {"type":"scroll","direction":"down|up"}
- {"type":"tap_text","text":"<visible label>"}
- {"type":"type_text","text":"..."}  (types into the currently focused field)
- {"type":"forget","text":"<fragment of a remembered fact to delete>"}

Rules:
- Questions and chat: put the answer in "speech" with an empty actions list. Use web search for anything current or uncertain.
- "speech" is read aloud: at most 3 short sentences, plain text, no markdown, lists or emoji.
- You cannot switch Wi-Fi or Bluetooth on or off directly; open the settings page and tell the user.
- Never tap anything that sends, pays, buys, deletes or confirms something irreversible. Tell the user to do that step themselves.
- Never claim you did something you cannot do. If a request is unclear, ask one short question in "speech".
- Put in "remember" only stable facts or preferences the user states about themselves (name, family, habits, preferences). Never passwords, PINs, card or ID numbers.
- Order actions in the order they should run.

Today is ${LocalDate.now()}.
What you know about the user:
$known
""".trimIndent()
    }
}
