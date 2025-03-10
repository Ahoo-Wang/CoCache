package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.api.Cache
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(endpoint.total(), hasSize(1))
    }

    @Test
    fun stat() {
        assertThat(endpoint.stat(CACHE_NAME), notNullValue())
    }

    @Test
    fun evict() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "evict-key"
        cache[key] = "value"
        endpoint.evict(CACHE_NAME, key)
        assertThat(cache[key], nullValue())
    }

    @Test
    fun get() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "get-key"
        cache[key] = "value"
        assertThat(endpoint.get(CACHE_NAME, key)?.value, equalTo("value"))
    }
}
