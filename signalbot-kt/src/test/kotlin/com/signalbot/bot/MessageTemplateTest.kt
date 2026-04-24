package com.signalbot.bot

import com.signalbot.signal.Member
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MessageTemplateTest {

    @Test
    fun `renders member variables`() {
        val t = MessageTemplate("Hi {{member_number}} / {{member_uuid}}")
        val out = t.render(Member(uuid = "u-1", number = "+5"))
        assertEquals("Hi +5 / u-1", out)
    }

    @Test
    fun `keeps unknown variables untouched`() {
        val t = MessageTemplate("Hello {{member_number}}, order {{order_id}}")
        val out = t.render(Member(number = "+1"))
        assertEquals("Hello +1, order {{order_id}}", out)
    }

    @Test
    fun `date and datetime are non-empty`() {
        val t = MessageTemplate("{{date}} {{datetime}} {{time}} {{timestamp}}")
        val out = t.render(Member())
        assertTrue(out.isNotBlank())
        assertFalse(out.contains("{{"))
    }
}
