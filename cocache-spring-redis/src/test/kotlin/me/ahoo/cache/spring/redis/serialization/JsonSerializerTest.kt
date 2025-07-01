package me.ahoo.cache.spring.redis.serialization

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class JsonSerializerTest {
    @Test
    fun toJson() {
        val json = JsonSerializer.writeValueAsString("hello world")
        json.assert().isEqualTo("\"hello world\"")
    }
}
