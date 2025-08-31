package ui

import agent.StepType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class MemoryEvent {
    data class Saved(val op: StepType, val hint: String, val selector: String) : MemoryEvent()
}

object MemoryBus {
    val events = MutableSharedFlow<MemoryEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun tryEmit(e: MemoryEvent) {
        events.tryEmit(e)
    }
}
