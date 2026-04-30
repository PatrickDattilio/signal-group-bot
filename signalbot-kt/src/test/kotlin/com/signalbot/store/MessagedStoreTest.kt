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
    fun `markMessaged sets getMessagedAt`() {
        val store = MessagedStore()
        val member = Member(uuid = "test-uuid")
        assertNull(store.getMessagedAt(member))
        store.markMessaged(member)
        assertNotNull(store.getMessagedAt(member))
    }

    @Test
    fun `getMessagedAt within generic cooldown window`() {
        val store = MessagedStore()
        val member = Member(uuid = "test-uuid")
        store.markMessaged(member)
        assertTrue(lastTouchWithinCooldown(store, member, cooldownSeconds = 10))
        store.markMessaged(member, timestamp = System.currentTimeMillis() / 1000.0 - 20)
        assertFalse(lastTouchWithinCooldown(store, member, cooldownSeconds = 10))
    }

    @Test
    fun `vetting cooldown ignores welcome-only contact`() {
        val store = MessagedStore()
        val member = Member(uuid = "test-uuid")
        store.markWelcomeSent(member)
        assertNotNull(store.getMessagedAt(member))
        assertFalse(store.isWithinVettingCooldown(member, cooldownSeconds = 10))
    }

    @Test
    fun `persistence across instances`() {
        val member = Member(uuid = "test-uuid")
        MessagedStore().markMessaged(member)
        assertNotNull(MessagedStore().getMessagedAt(member))
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
    fun `member key lowercases uuid`() {
        assertEquals("uuid:aa-bb", Member(uuid = "AA-BB").key())
    }

    @Test
    fun `member key collapses ascii spaces in number`() {
        assertEquals("number:+1123", Member(number = " +11 23 ").key())
    }

    @Test
    fun `markVettingSent refreshes vetting_sent_at for repeated intro cooldown`() {
        val store = MessagedStore()
        val member = Member(uuid = "refresh-cooldown-test")
        val stale = 1_000.0
        store.importAll(mapOf("uuid:refresh-cooldown-test" to stale))
        assertEquals(stale, store.getRow(member)?.vettingSentAt)
        val now = System.currentTimeMillis() / 1000.0
        store.markVettingSent(member, timestamp = now)
        assertEquals(now, store.getRow(member)?.vettingSentAt)
    }

    @Test
    fun `vetting cooldown merges legacy uuid casing row`() {
        val store = MessagedStore()
        val now = System.currentTimeMillis() / 1000.0
        store.importAll(mapOf("uuid:AAAA0000-0000-0000-0000-000000000099" to now))
        assertTrue(
            store.isWithinVettingCooldown(
                Member(uuid = "aaaa0000-0000-0000-0000-000000000099"),
                cooldownSeconds = 86_400,
            ),
        )
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

    private fun lastTouchWithinCooldown(store: MessagedStore, member: Member, cooldownSeconds: Int): Boolean {
        if (cooldownSeconds <= 0) return store.getMessagedAt(member) != null
        val ts = store.getMessagedAt(member) ?: return false
        return (System.currentTimeMillis() / 1000.0 - ts) < cooldownSeconds
    }
}
