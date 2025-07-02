package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.api.Cache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoCacheClientEndpointTest : AbstractCoCacheEndpointTest() {
    private lateinit var endpoint: CoCacheClientEndpoint

    @BeforeEach
    override fun setup() {
        super.setup()
        endpoint = CoCacheClientEndpoint(cacheFactory)
    }

    @Test
    fun getSize() {
        endpoint.getSize(CACHE_NAME).assert().isEqualTo(0)
    }

    @Test
    fun getSizeWhenNotFound() {
        endpoint.getSize(NOT_FOUND).assert().isNull()
    }

    @Test
    fun get() {
        endpoint.get(CACHE_NAME, "key").assert().isNull()
    }

    @Test
    fun getWhenNotFound() {
        endpoint.get(NOT_FOUND, "key").assert().isNull()
    }

    @Test
    fun clear() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "clear-key"
        cache[key] = "value"
        endpoint.getSize(CACHE_NAME).assert().isOne()
        endpoint.clear(CACHE_NAME)
        endpoint.getSize(CACHE_NAME).assert().isZero()
    }

    @Test
    fun clearWhenNotFound() {
        endpoint.clear(NOT_FOUND)
    }
}
