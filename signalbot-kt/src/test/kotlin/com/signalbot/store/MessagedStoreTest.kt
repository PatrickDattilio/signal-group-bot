package com.signalbot.store

import com.signalbot.TestDatabase
import com.signalbot.signal.Member
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MessagedStoreTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.connect()
    }

    @Test
    fun `new store is empty`() {
        val store = MessagedStore()
        assertEquals(0, store.getStats().totalMembers)
    }

    @Test
    fun `mark and check messaged`() {
        val store = MessagedStore()
        val member = Member(uuid = "test-uuid")
        assertFalse(store.wasMessaged(member))
        store.markMessaged(member)
        assertTrue(store.wasMessaged(member))
    }

    @Test
    fun `respects cooldown`() {
        val store = MessagedStore()
        val member = Member(uuid = "test-uuid")
        store.markMessaged(member)
        assertTrue(store.wasMessaged(member, cooldownSeconds = 10))
        // Overwrite timestamp to 20s ago
        store.markMessaged(member, timestamp = System.currentTimeMillis() / 1000.0 - 20)
        assertFalse(store.wasMessaged(member, cooldownSeconds = 10))
    }

    @Test
    fun `persistence across instances`() {
        val member = Member(uuid = "test-uuid")
        MessagedStore().markMessaged(member)
        assertTrue(MessagedStore().wasMessaged(member))
    }

    @Test
    fun `stats counts members by age`() {
        val store = MessagedStore()
        for (i in 0 until 5) store.markMessaged(Member(uuid = "uuid-$i"))
        val s = store.getStats()
        assertEquals(5L, s.totalMembers)
        assertEquals(5L, s.last24h)
    }

    @Test
    fun `member key prefers uuid`() {
        val m = Member(uuid = "x", number = "+123")
        assertEquals("uuid:x", m.key())
    }

    @Test
    fun `member key falls back to number`() {
        val m = Member(number = "+1")
        assertEquals("number:+1", m.key())
    }

    @Test
    fun `empty member key`() {
        assertEquals("{}", Member().key())
    }
}
