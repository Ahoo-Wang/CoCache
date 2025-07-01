package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.api.Cache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoCacheEndpointTest : AbstractCoCacheEndpointTest() {
    private lateinit var endpoint: CoCacheEndpoint

    @BeforeEach
    override fun setup() {
        super.setup()
        endpoint = CoCacheEndpoint(cacheFactory)
    }

    @Test
    fun total() {
        endpoint.total().assert().hasSize(1)
    }

    @Test
    fun stat() {
        endpoint.stat(CACHE_NAME).assert().isNotNull()
    }

    @Test
    fun evict() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "evict-key"
        cache[key] = "value"
        endpoint.evict(CACHE_NAME, key)
        cache[key].assert().isNull()
    }

    @Test
    fun get() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "get-key"
        cache[key] = "value"
        endpoint.get(CACHE_NAME, key)?.value.assert().isEqualTo("value")
    }
}
