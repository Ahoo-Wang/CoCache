package me.ahoo.cache.spring.redis.serialization

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonSerializerTest {
    @Test
    fun toJson() {
        val json = JsonSerializer.writeValueAsString("hello world")
        assertEquals("\"hello world\"", json)
    }
}
