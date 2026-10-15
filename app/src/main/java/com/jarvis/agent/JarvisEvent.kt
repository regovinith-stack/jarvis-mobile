package com.jarvis.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class JarvisEvent {
    data class Status(val text: String) : JarvisEvent()
    data class UserSaid(val text: String) : JarvisEvent()
    data class Reply(val text: String) : JarvisEvent()
    data class Error(val message: String) : JarvisEvent()
}

object JarvisEventBus {
    private val _events = MutableSharedFlow<JarvisEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<JarvisEvent> = _events.asSharedFlow()
    fun emit(event: JarvisEvent) {
        _events.tryEmit(event)
    }
}
