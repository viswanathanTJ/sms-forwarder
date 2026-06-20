package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudUploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fanOut(id: String) = CloudMessageRepository.FanOut(
        messageId = id, sourceDeviceId = "S", sourceAlias = "Phone",
        ciphertextB64 = "Yw==", nonceB64 = "bg==",
        copies = listOf(CloudMessageRepository.RecipientCopy("R", "dw==")),
    )

    @Test
    fun enqueue_pending_remove_roundTrips() {
        val q = CloudUploadQueue(tmp.newFolder("queue"))
        val a = fanOut("a"); val b = fanOut("b")
        q.enqueue(a); q.enqueue(b)
        assertEquals(2, q.pending().size)
        q.remove(a)
        assertEquals(listOf("b"), q.pending().map { it.messageId })
    }
}
