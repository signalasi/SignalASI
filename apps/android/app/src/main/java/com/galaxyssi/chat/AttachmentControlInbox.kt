package com.galaxyssi.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** A durable inbox entry is complete only after its background handler commits. */
internal class AttachmentControlInbox(private val executor: Executor) {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(id: String, process: () -> Unit, complete: () -> Unit, failed: (Exception) -> Unit) {
        require(id.isNotBlank())
        if (!pending.add(id)) return
        try {
            executor.execute {
                try {
                    process()
                    complete()
                } catch (error: Exception) {
                    failed(error)
                } finally {
                    pending.remove(id)
                }
            }
        } catch (error: Exception) {
            pending.remove(id)
            failed(error)
        }
    }
}
