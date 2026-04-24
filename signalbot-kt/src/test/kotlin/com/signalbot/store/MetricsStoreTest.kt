package com.signalbot.store

import com.signalbot.TestDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MetricsStoreTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.connect()
    }

    @Test
    fun `new metrics initialized to zero`() {
        val m = MetricsStore()
        val s = m.getStats()
        assertEquals(0, s.messagesSent)
        assertEquals(0, s.messagesFailed)
        assertEquals(0, s.approvalsSucceeded)
        assertEquals(0, s.approvalsFailed)
        assertEquals(0, s.pollsCompleted)
        assertEquals(0, s.pollsFailed)
    }

    @Test
    fun `record message sent`() {
        val m = MetricsStore()
        m.recordMessageSent()
        m.recordMessageSent()
        assertEquals(2, m.getStats().messagesSent)
    }

    @Test
    fun `record message failed tracks error type`() {
        val m = MetricsStore()
        m.recordMessageFailed("timeout")
        m.recordMessageFailed("timeout")
        m.recordMessageFailed("connection_error")
        val s = m.getStats()
        assertEquals(3, s.messagesFailed)
        assertEquals(2L, s.errors["timeout"])
        assertEquals(1L, s.errors["connection_error"])
    }

    @Test
    fun `success rate calculation`() {
        val m = MetricsStore()
        m.recordMessageSent(); m.recordMessageSent(); m.recordMessageSent()
        m.recordMessageFailed()
        assertEquals(75.0, m.getStats().messageSuccessRate)
    }

    @Test
    fun `success rate is null when no data`() {
        val m = MetricsStore()
        val s = m.getStats()
        assertNull(s.messageSuccessRate)
        assertNull(s.approvalSuccessRate)
        assertNull(s.pollSuccessRate)
    }

    @Test
    fun `metrics persist across instances`() {
        val m1 = MetricsStore()
        m1.recordMessageSent()
        m1.recordApprovalSucceeded()
        val s = MetricsStore().getStats()
        assertEquals(1, s.messagesSent)
        assertEquals(1, s.approvalsSucceeded)
    }

    @Test
    fun `reset clears metrics`() {
        val m = MetricsStore()
        m.recordMessageSent(); m.recordApprovalSucceeded(); m.recordPollCompleted()
        m.reset()
        val s = m.getStats()
        assertEquals(0, s.messagesSent)
        assertEquals(0, s.approvalsSucceeded)
        assertEquals(0, s.pollsCompleted)
    }

    @Test
    fun `disabled metrics do not write`() {
        val m = MetricsStore(enabled = false)
        m.recordMessageSent()
        val s = m.getStats()
        assertEquals(0, s.messagesSent)
    }
}
