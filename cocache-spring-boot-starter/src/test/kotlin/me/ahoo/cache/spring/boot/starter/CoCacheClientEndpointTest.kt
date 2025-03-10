package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.api.Cache
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(endpoint.getSize(CACHE_NAME), equalTo(0))
    }

    @Test
    fun getSizeWhenNull() {
        assertThat(endpoint.getSize("CACHE_NAME"), nullValue())
    }

    @Test
    fun get() {
        assertThat(endpoint.get(CACHE_NAME, "key"), nullValue())
    }

    @Test
    fun clear() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "clear-key"
        cache[key] = "value"
        assertThat(endpoint.getSize(CACHE_NAME), equalTo(1))
        endpoint.clear(CACHE_NAME)
        assertThat(endpoint.getSize(CACHE_NAME), equalTo(0))
    }
}
