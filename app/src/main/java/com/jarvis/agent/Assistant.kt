package com.jarvis.agent

import android.content.Context
import kotlinx.coroutines.delay

object Assistant {
    /** Call on the main thread. Thinks, runs the actions, updates memory, returns what to say. */
    suspend fun handle(context: Context, text: String): String {
        val app = context.applicationContext
        val memory = Memory(app)
        val result = Brain.think(app, text)

        result.remember.forEach { memory.addFact(it) }

        val notes = mutableListOf<String>()
        for (action in result.actions) {
            if (action.optString("type") == "forget") {
                memory.forget(action.optString("text"))
                continue
            }
            ActionExecutor.run(app, action)?.let { notes.add(it) }
            delay(700) // let the screen settle before the next step
        }

        val reply = (listOf(result.speech) + notes).filter { it.isNotBlank() }.joinToString(" ")
        return reply.ifBlank { "Done." }
    }
}
