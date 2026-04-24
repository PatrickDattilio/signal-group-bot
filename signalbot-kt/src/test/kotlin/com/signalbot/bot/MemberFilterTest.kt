package com.signalbot.bot

import com.signalbot.signal.Member
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemberFilterTest {

    @Test
    fun `default allows everyone`() {
        val f = MemberFilter()
        val r = f.shouldApprove(Member(uuid = "u1", number = "+1"))
        assertTrue(r.shouldApprove)
    }

    @Test
    fun `blocklist blocks exact match`() {
        val f = MemberFilter(blocklist = listOf("+15555551234"))
        val r = f.shouldApprove(Member(number = "+15555551234"))
        assertFalse(r.shouldApprove)
    }

    @Test
    fun `wildcard pattern`() {
        val f = MemberFilter(blocklist = listOf("*spam*"))
        val r = f.shouldApprove(Member(uuid = "uuid-spam-bot"))
        assertFalse(r.shouldApprove)
    }

    @Test
    fun `allowlist enabled only allows matching`() {
        val f = MemberFilter(allowlist = listOf("+1234*"), allowlistEnabled = true)
        assertTrue(f.shouldApprove(Member(number = "+12345")).shouldApprove)
        assertFalse(f.shouldApprove(Member(number = "+9999")).shouldApprove)
    }

    @Test
    fun `rate limiter allows within window`() {
        val rl = RateLimiter(maxRequests = 2, windowSeconds = 60)
        val m = Member(uuid = "u")
        val now = 1_000_000.0
        assertTrue(rl.check(m, now).allowed)
        assertTrue(rl.check(m, now).allowed)
        assertFalse(rl.check(m, now).allowed)
    }

    @Test
    fun `rate limiter evicts old entries`() {
        val rl = RateLimiter(maxRequests = 2, windowSeconds = 60)
        val m = Member(uuid = "u")
        rl.check(m, 1_000.0)
        rl.check(m, 1_001.0)
        assertFalse(rl.check(m, 1_002.0).allowed)
        // Long after window
        assertTrue(rl.check(m, 1_000_000.0).allowed)
    }
}
