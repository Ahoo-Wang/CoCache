/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.spring.cache

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.tx.TransactionAwareSpringCacheManager
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.cache.Cache as SpringCache

class DegradingCacheErrorHandlerTest {
    private val backend = InMemoryBackend()
    private val runtime = CacheRuntime.builder().backend(backend).build().apply {
        create(cacheSpec<String, String>("user") { codec = StringCodec })
    }
    private val cocache = requireNotNull(CoCacheSpringCacheManager(runtime).getCache("user"))
    private val decorated = requireNotNull(
        TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)).getCache("user")
    )
    private val other = ConcurrentMapCache("other")
    private val delegate = RecordingErrorHandler()
    private val handler = DegradingCacheErrorHandler(delegate)
    private val failure = IllegalStateException("remote down")

    @AfterEach
    fun destroy() {
        runtime.close()
        backend.close()
    }

    /**
     * CoCache 缓存（包括事务装饰器）的读取失败与非 null 的写入失败只记录告警（不带堆栈，不记录 key），不交给原来的处理器。
     */
    @Test
    fun readAndWriteFailuresOfCoCacheCachesDegrade() {
        val logs = captureLogs {
            handler.handleCacheGetError(failure, cocache, "secret-key")
            handler.handleCachePutError(failure, cocache, "secret-key", "v")
            handler.handleCacheGetError(failure, decorated, "secret-key")
            handler.handleCachePutError(failure, decorated, "secret-key", "v")
        }
        delegate.events.assert().isEmpty()
        val warnings = logs.filter { it.level == Level.WARN }
        warnings.map { it.formattedMessage }.assert().hasSize(4)
        warnings.first().formattedMessage.assert().contains("Cache[user]", "read failed", "remote down")
        warnings[1].formattedMessage.assert().contains("Cache[user]", "write failed")
        warnings.forEach {
            it.throwableProxy.assert().isNull()
            it.formattedMessage.assert().doesNotContain("secret-key")
        }
    }

    /**
     * 失效失败不放行：`evict`、映射为失效的 `put(key, null)` 与 `clear` 交给原来的处理器；其它缓存的全部失败同样。
     */
    @Test
    fun invalidationsAndOtherCachesGoToTheDelegate() {
        handler.handleCachePutError(failure, cocache, "k", null)
        handler.handleCacheEvictError(failure, cocache, "k")
        handler.handleCacheClearError(failure, cocache)
        handler.handleCacheGetError(failure, other, "k")
        handler.handleCachePutError(failure, other, "k", "v")
        handler.handleCacheEvictError(failure, other, "k")
        handler.handleCacheClearError(failure, other)
        delegate.events.assert().containsExactly(
            "put:user:k:null",
            "evict:user:k",
            "clear:user",
            "get:other:k",
            "put:other:k:v",
            "evict:other:k",
            "clear:other",
        )
    }

    @Test
    fun defaultDelegateIsSpringsSimpleHandler() {
        val default = DegradingCacheErrorHandler()
        default.delegate.assert().isInstanceOf(SimpleCacheErrorHandler::class.java)
        runCatching { default.handleCacheGetError(failure, other, "k") }.exceptionOrNull().assert().isSameAs(failure)
        default.handleCacheGetError(failure, cocache, "k")
    }

    @Test
    fun debugLevelLogsTheStackTrace() {
        val logs = captureLogs(Level.DEBUG) { handler.handleCacheGetError(failure, cocache, "k") }
        val debug = logs.single { it.level == Level.DEBUG }
        debug.throwableProxy?.message.assert().isEqualTo("remote down")
        logs.none { it.level == Level.WARN }.assert().isTrue()
    }

    private fun captureLogs(level: Level = Level.INFO, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DegradingCacheErrorHandler::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = level
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
        return appender.list
    }

    private class RecordingErrorHandler : CacheErrorHandler {
        val events = CopyOnWriteArrayList<String>()

        override fun handleCacheGetError(exception: RuntimeException, cache: SpringCache, key: Any) {
            events += "get:${cache.name}:$key"
        }

        override fun handleCachePutError(exception: RuntimeException, cache: SpringCache, key: Any, value: Any?) {
            events += "put:${cache.name}:$key:$value"
        }

        override fun handleCacheEvictError(exception: RuntimeException, cache: SpringCache, key: Any) {
            events += "evict:${cache.name}:$key"
        }

        override fun handleCacheClearError(exception: RuntimeException, cache: SpringCache) {
            events += "clear:${cache.name}"
        }
    }
}
