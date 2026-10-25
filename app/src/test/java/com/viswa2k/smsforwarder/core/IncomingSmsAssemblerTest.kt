package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingSmsAssemblerTest {
    @Test fun `multipart PDUs from one sender join into one message`() {
        val out = IncomingSmsAssembler.assemble(
            listOf(
                Pdu("VM-HDFCBK", "Rs.1250.00 debited from A/c XX4521 on 25-10-26. ", 5_000, 3),
                Pdu("VM-HDFCBK", "Avl Bal Rs.23410.55", 5_000, 3),
            ),
            receivedAt = 9_000,
        )
        assertEquals(1, out.size)
        assertEquals("Rs.1250.00 debited from A/c XX4521 on 25-10-26. Avl Bal Rs.23410.55", out[0].body)
        assertEquals("VM-HDFCBK", out[0].sender)
        assertEquals(5_000L, out[0].sentAt)
        assertEquals(9_000L, out[0].receivedAt)
        assertEquals(3, out[0].subscriptionId)
    }

    @Test fun `two senders give two messages in order`() {
        val out = IncomingSmsAssembler.assemble(
            listOf(Pdu("AX-ONE", "a", 1, null), Pdu("+919840012345", "b", 2, null)), 10,
        )
        assertEquals(listOf("a", "b"), out.map { it.body })
    }

    @Test fun `empty input gives nothing`() {
        assertEquals(emptyList<IncomingSms>(), IncomingSmsAssembler.assemble(emptyList(), 1))
    }
}
