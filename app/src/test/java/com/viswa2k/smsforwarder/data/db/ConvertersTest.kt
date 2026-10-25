package com.viswa2k.smsforwarder.data.db

import com.viswa2k.smsforwarder.core.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    @Test fun typesRoundTrip() {
        val set = setOf(MessageType.OTP, MessageType.TRANSACTION)
        assertEquals("2,3", Converters.typesToCsv(set))
        assertEquals(set, Converters.csvToTypes("2,3"))
    }

    @Test fun emptyTypes() {
        assertEquals("", Converters.typesToCsv(emptySet()))
        assertEquals(emptySet<MessageType>(), Converters.csvToTypes(""))
    }

    @Test fun unknownTypeCodesDropped() =
        assertEquals(setOf(MessageType.OTP), Converters.csvToTypes("2,99,x"))

    @Test fun idsRoundTrip() {
        assertEquals("5,1,9", Converters.idsToCsv(listOf(5L, 1L, 9L)))
        assertEquals(listOf(5L, 1L, 9L), Converters.csvToIds("5,1,9"))
        assertEquals(emptyList<Long>(), Converters.csvToIds(""))
    }
}
