package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpWindowTest {
    private val hour = 3_600_000L
    private val now = 100 * hour

    @Test fun `floor inside the window wins`() = assertEquals(now - hour, CatchUpWindow.since(now, now - hour))
    @Test fun `no floor gives 24 hours`() = assertEquals(now - 24 * hour, CatchUpWindow.since(now, 0))
    @Test fun `old floor never widens the window`() = assertEquals(now - 24 * hour, CatchUpWindow.since(now, now - 72 * hour))
    @Test fun `floor in the future is returned (nothing qualifies)`() = assertEquals(now + hour, CatchUpWindow.since(now, now + hour))
}
