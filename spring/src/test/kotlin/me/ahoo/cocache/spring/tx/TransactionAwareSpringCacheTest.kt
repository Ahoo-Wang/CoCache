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

package me.ahoo.cocache.spring.tx

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCache
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.cache.Cache as SpringCache

class TransactionAwareSpringCacheTest {
    private val transactions = InMemoryTransactionManager()
    private val tx = TransactionTemplate(transactions)
    private val nestedTx = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }
    private val newTx = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val memory = InMemoryBackend()
    private val backend = HookedBackend(memory)
    private val runtime = CacheRuntime.builder().backend(backend).build()

    /**
     * 与 Spring Cache 的通常用法一样是外部来源：被注解的方法是数据源，`get(key)` 不回源。
     */
    private val user: Cache<String, String> = runtime.create(
        cacheSpec("user") {
            codec = StringCodec
            externallySourced()
        }
    )
    private val errors = RecordingErrorHandler()
    private val manager = TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)) { errors }
    private val cache: SpringCache = requireNotNull(manager.getCache("user"))

    @AfterEach
    fun destroy() {
        runtime.close()
        memory.close()
    }

    @Test
    fun managerDecoratesAndCachesTheRuntimeCaches() {
        manager.cacheNames.assert().containsExactly("user")
        manager.getCache("user").assert().isSameAs(cache)
        manager.getCache("absent").assert().isNull()
        val decorated = cache as TransactionAwareSpringCache
        decorated.targetCache.assert().isSameAs(manager.targetCacheManager.getCache("user"))
        cache.name.assert().isEqualTo("user")
        cache.nativeCache.assert().isSameAs(runtime.cache("user"))
    }

    @Test
    fun readsDelegate() {
        user.put("k", "v")
        cache.get("k")?.get().assert().isEqualTo("v")
        cache.get("k", String::class.java).assert().isEqualTo("v")
        cache.get("loaded") { "loaded-value" }.assert().isEqualTo("loaded-value")
        (cache.retrieve("k")?.get() as SpringCache.ValueWrapper).get().assert().isEqualTo("v")
        cache.retrieve("async") { CompletableFuture.completedFuture("A") }.get().assert().isEqualTo("A")
        cache.putIfAbsent("k", "other")?.get().assert().isEqualTo("v")
        cache.putIfAbsent("new", "n").assert().isNull()
        user["new"].assert().isEqualTo("n")
    }

    @Test
    fun outsideTransactionEveryOperationRunsDirectly() {
        cache.put("a", "1")
        cache.get("a")?.get().assert().isEqualTo("1")
        cache.evict("a")
        cache.get("a").assert().isNull()
        cache.put("b", "2")
        cache.put("b", null)
        cache.get("b").assert().isNull()
        cache.put("c", "3")
        cache.evictIfPresent("c").assert().isFalse()
        cache.get("c").assert().isNull()
        user.put("d", "4")
        user["d"].assert().isEqualTo("4")
        cache.clear()
        runtime.admin("user")?.localSize.assert().isEqualTo(0)
        errors.events.assert().isEmpty()
    }

    @Test
    fun putIsDeferredUntilCommitAndDroppedOnRollback() {
        tx.executeWithoutResult {
            cache.put("a", "1")
            cache.get("a").assert().isNull()
        }
        cache.get("a")?.get().assert().isEqualTo("1")
        tx.executeWithoutResult { status ->
            cache.put("b", "2")
            status.setRollbackOnly()
        }
        cache.get("b").assert().isNull()
    }

    @Test
    fun evictInvalidatesAfterCommit() {
        user.put("a", "old")
        tx.executeWithoutResult {
            transactions.write("a", "new")
            cache.evict("a")
            cache.get("a")?.get().assert().isEqualTo("old")
        }
        cache.get("a").assert().isNull()
        readThrough("a").assert().isEqualTo("new")
    }

    /**
     * 缺陷复现（U3）：Spring 的装饰器只在提交之后失效。事务内先 `@CacheEvict`，同一事务内的读穿透再把未提交的行写入 L1，
     * 回滚之后这个从未提交的值留到 `ttl`。
     */
    @Test
    fun evictInvalidatesOnRollbackToo() {
        tx.executeWithoutResult { status ->
            transactions.write("a", "draft")
            cache.evict("a")
            readThrough("a").assert().isEqualTo("draft")
            status.setRollbackOnly()
        }
        cache.get("a").assert().isNull()
        readThrough("a").assert().isNull()
    }

    /**
     * `put(key, null)` 映射为失效，按 evict 登记：回滚时同样失效。
     */
    @Test
    fun nullPutIsRegisteredAsAnInvalidation() {
        tx.executeWithoutResult { status ->
            transactions.write("a", "draft")
            cache.put("a", null)
            readThrough("a").assert().isEqualTo("draft")
            status.setRollbackOnly()
        }
        cache.get("a").assert().isNull()
        user.put("b", "old")
        tx.executeWithoutResult {
            cache.put("b", null)
            cache.get("b")?.get().assert().isEqualTo("old")
        }
        cache.get("b").assert().isNull()
    }

    /**
     * `evictIfPresent`（`@CacheEvict(beforeInvocation = true)`）立即失效，提交之后再失效一次：提交之前在事务之外读穿透写回的
     * 已提交旧值不留下（S5）。
     */
    @Test
    fun evictIfPresentInvalidatesAtOnceAndAgainAfterCommit() {
        tx.executeWithoutResult { transactions.write("a", "old") }
        readThrough("a").assert().isEqualTo("old")
        tx.executeWithoutResult {
            transactions.write("a", "new")
            cache.evictIfPresent("a").assert().isFalse()
            cache.get("a").assert().isNull()
            fork { readThrough("a") }.join().assert().isEqualTo("old")
        }
        cache.get("a").assert().isNull()
        readThrough("a").assert().isEqualTo("new")
    }

    @Test
    fun invalidateRunsAtOnceAndClearIsDeferredUntilCommit() {
        user.put("a", "1")
        user["a"].assert().isEqualTo("1")
        val admin = requireNotNull(runtime.admin("user"))
        admin.localSize.assert().isEqualTo(1)
        tx.executeWithoutResult {
            cache.invalidate().assert().isFalse()
            admin.localSize.assert().isEqualTo(0)
        }
        user["a"].assert().isEqualTo("1")
        tx.executeWithoutResult { status ->
            cache.clear()
            status.setRollbackOnly()
        }
        admin.localSize.assert().isEqualTo(1)
        tx.executeWithoutResult {
            cache.clear()
            admin.localSize.assert().isEqualTo(1)
        }
        admin.localSize.assert().isEqualTo(0)
    }

    /**
     * 写入之后对同一 key 登记的失效取代它（`@CachePut` 之后的 `@CacheEvict`），否则提交之后写回的是被删除之前的值；
     * 写入之前登记的失效不影响它。按缓存对象匹配：经 [TransactionalInvalidator] 登记同一个引擎也取代写入。
     */
    @Test
    fun laterInvalidationSupersedesAnEarlierPut() {
        @Suppress("UNCHECKED_CAST")
        val native = cache.nativeCache as Cache<String, String>
        tx.executeWithoutResult {
            cache.put("a", "1")
            cache.evict("a")
            cache.evict("b")
            cache.put("b", "2")
            cache.evict("unrelated")
            cache.put("c", "3")
            cache.evict("c")
            cache.put("d", "4")
            cache.evictIfPresent("d")
            cache.put("e", "5")
            TransactionalInvalidator().invalidate(native, "e")
            cache.put("f", "6")
            cache.put("f", "7")
        }
        cache.get("a").assert().isNull()
        cache.get("b")?.get().assert().isEqualTo("2")
        cache.get("c").assert().isNull()
        cache.get("d").assert().isNull()
        cache.get("e").assert().isNull()
        cache.get("f")?.get().assert().isEqualTo("7")
    }

    /**
     * 回滚到 savepoint（`NESTED`）时丢弃 savepoint 之后登记的写入，之前登记的照常在提交之后写入；正常结束的嵌套段不受影响。
     * 嵌套段内登记的失效在回滚到 savepoint 时立即执行。
     */
    @Test
    fun savepointRollbackDropsTheWritesOfTheRolledBackSegment() {
        tx.executeWithoutResult {
            cache.put("outer", "o")
            nestedTx.executeWithoutResult { nested ->
                cache.put("inner", "i")
                cache.put("outer", "changed")
                transactions.write("evicted", "draft")
                cache.evict("evicted")
                readThrough("evicted").assert().isEqualTo("draft")
                nested.setRollbackOnly()
            }
            cache.get("evicted").assert().isNull()
            nestedTx.executeWithoutResult { cache.put("released", "r") }
            cache.put("after", "a")
        }
        cache.get("outer")?.get().assert().isEqualTo("o")
        cache.get("inner").assert().isNull()
        cache.get("released")?.get().assert().isEqualTo("r")
        cache.get("after")?.get().assert().isEqualTo("a")
        readThrough("evicted").assert().isNull()
    }

    /**
     * 第一次登记发生在嵌套段内：同步注册于 savepoint 创建之后，回滚到它时丢弃全部写入。
     */
    @Test
    fun savepointCreatedBeforeTheFirstRegistrationDropsEveryWrite() {
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                cache.put("first", "f")
                nested.setRollbackOnly()
            }
            cache.put("after", "a")
        }
        cache.get("first").assert().isNull()
        cache.get("after")?.get().assert().isEqualTo("a")
    }

    /**
     * 编程式 savepoint：回滚到它之后 savepoint 仍然有效，之后的写入照常执行。
     */
    @Test
    fun programmaticSavepointRollbackDropsOnlyTheLaterWrites() {
        tx.executeWithoutResult { status ->
            cache.put("a", "1")
            val savepoint = status.createSavepoint()
            cache.put("b", "2")
            status.rollbackToSavepoint(savepoint)
            cache.put("c", "3")
            status.rollbackToSavepoint(savepoint)
            cache.put("d", "4")
            status.releaseSavepoint(savepoint)
        }
        cache.get("a")?.get().assert().isEqualTo("1")
        cache.get("b").assert().isNull()
        cache.get("c").assert().isNull()
        cache.get("d")?.get().assert().isEqualTo("4")
    }

    @Test
    fun innerNewTransactionWritesOnItsOwnCommit() {
        tx.executeWithoutResult {
            cache.put("outer", "o")
            newTx.executeWithoutResult { cache.put("inner", "i") }
            cache.get("inner")?.get().assert().isEqualTo("i")
            cache.get("outer").assert().isNull()
        }
        cache.get("outer")?.get().assert().isEqualTo("o")
    }

    /**
     * 缺陷复现：第一次登记发生在另一个同步的 `afterCommit` 中（Spring 不再对它调用 `afterCommit`），在 `afterCompletion`
     * 中按提交执行；本事务已经完成之后登记的写入立即执行。
     */
    @Test
    fun writesRegisteredInAnotherAfterCommitAreNotLost() {
        tx.executeWithoutResult {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = cache.put("first", "f")
            })
        }
        cache.get("first")?.get().assert().isEqualTo("f")
        tx.executeWithoutResult {
            cache.put("a", "1")
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    cache.put("late", "l")
                    cache.get("late")?.get().assert().isEqualTo("l")
                }
            })
        }
        cache.get("a")?.get().assert().isEqualTo("1")
        cache.get("late")?.get().assert().isEqualTo("l")
    }

    /**
     * 排在前面的同步在 `afterCommit` 中抛出时，Spring 不再调用其余同步的 `afterCommit`：写入在 `afterCompletion` 中执行。
     */
    @Test
    fun earlierFailingAfterCommitDoesNotDropWrites() {
        val failure = IllegalStateException("listener failed")
        val error = runCatching {
            tx.executeWithoutResult {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = throw failure
                })
                cache.put("a", "1")
            }
        }.exceptionOrNull()
        error.assert().isSameAs(failure)
        cache.get("a")?.get().assert().isEqualTo("1")
    }

    /**
     * 缺陷复现（S18）：提交之后的 put 与 evict 失败交给 `CacheErrorHandler`，不抛给调用方（数据源已经提交），
     * 其余同步的 `afterCommit` 照常执行。
     */
    @Test
    fun failuresAfterCommitGoToTheErrorHandler() {
        val failing = FailingCache()
        val decorated = TransactionAwareSpringCache(CoCacheSpringCache(failing, null, Runnable::run)) { errors }
        var laterAfterCommit = false
        tx.executeWithoutResult {
            decorated.put("a", "1")
            decorated.evict("b")
            decorated.put("c", null)
            // 立即失效的失败抛给调用方（缓存通知交给 CacheErrorHandler），事务结束之后的再失效照常登记
            runCatching { decorated.evictIfPresent("d") }.exceptionOrNull().assert().isSameAs(failing.invalidateFailure)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    laterAfterCommit = true
                }
            })
        }
        laterAfterCommit.assert().isTrue()
        // 先失效（同一去向的 key 合并为一次 invalidateAll），再执行写入
        errors.events.assert().containsExactly(
            "evict:b:${failing.invalidateFailure.message}",
            "evict:d:${failing.invalidateFailure.message}",
            "put:c:null:${failing.invalidateFailure.message}",
            "put:a:1:${failing.putFailure.message}",
        )
        errors.cache.assert().isSameAs(decorated)
    }

    /**
     * 回滚时的失效失败同样交给 `CacheErrorHandler`。非运行时异常包装为 `IllegalStateException`（`CacheErrorHandler` 只接受运行时异常）。
     */
    @Test
    fun failuresOnRollbackGoToTheErrorHandler() {
        val failing = FailingCache(invalidateFailure = IOException("remote down"))
        val decorated = TransactionAwareSpringCache(CoCacheSpringCache(failing, null, Runnable::run)) { errors }
        tx.executeWithoutResult { status ->
            decorated.evict("a")
            decorated.put("b", "2")
            status.setRollbackOnly()
        }
        errors.events.assert().containsExactly("evict:a:java.io.IOException: remote down")
        errors.causes.single().assert().isSameAs(failing.invalidateFailure)
    }

    @Test
    fun clearFailureAfterCommitGoesToTheErrorHandler() {
        val failure = IllegalStateException("clear failed")
        val admin = mockk<CacheAdmin> { every { clearLocal() } throws failure }
        val decorated = TransactionAwareSpringCache(CoCacheSpringCache(FailingCache(), admin, Runnable::run)) { errors }
        tx.executeWithoutResult { decorated.clear() }
        errors.events.assert().containsExactly("clear:clear failed")
    }

    /**
     * 处理器抛出（默认的 `SimpleCacheErrorHandler` 重新抛出）时只记录日志，不在提交之后抛给调用方。
     */
    @Test
    fun rethrowingErrorHandlerIsLoggedNotThrown() {
        val failing = FailingCache()
        val simple = TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime))
        val decorated = TransactionAwareSpringCache(CoCacheSpringCache(failing, null, Runnable::run)) {
            org.springframework.cache.interceptor.SimpleCacheErrorHandler()
        }
        val logs = captureLogs(TransactionAwareSpringCache::class.java) {
            tx.executeWithoutResult {
                decorated.put("a", "1")
                decorated.evict("b")
            }
            backend.failWrites = true
            try {
                tx.executeWithoutResult { requireNotNull(simple.getCache("user")).put("k", "v") }
            } finally {
                backend.failWrites = false
            }
        }
        val errorLogs = logs.filter { it.level == Level.ERROR }
        errorLogs.map { it.formattedMessage }.assert().hasSize(3)
        errorLogs.map { it.formattedMessage.substringBefore(" failed") }.assert().containsExactly(
            "Cache[user]: deferred evict",
            "Cache[user]: deferred put",
            "Cache[user]: deferred put"
        )
        user["k"].assert().isNull()
    }

    /**
     * 经引擎的 `get(key, loader)` 读穿透（`@Cacheable(sync = true)` 的路径）：loader 在调用方线程上参与事务。
     */
    private fun readThrough(key: String): String? = user.get(key, CacheLoader { transactions.read(it) })

    private fun captureLogs(type: Class<*>, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    /**
     * 记录每次调用：`操作:key[:value]:异常消息`。
     */
    private class RecordingErrorHandler : CacheErrorHandler {
        val events = CopyOnWriteArrayList<String>()
        val causes = CopyOnWriteArrayList<Throwable?>()

        @Volatile
        var cache: SpringCache? = null

        override fun handleCacheGetError(exception: RuntimeException, cache: SpringCache, key: Any) {
            record("get:$key", exception, cache)
        }

        override fun handleCachePutError(exception: RuntimeException, cache: SpringCache, key: Any, value: Any?) {
            record("put:$key:$value", exception, cache)
        }

        override fun handleCacheEvictError(exception: RuntimeException, cache: SpringCache, key: Any) {
            record("evict:$key", exception, cache)
        }

        override fun handleCacheClearError(exception: RuntimeException, cache: SpringCache) {
            record("clear", exception, cache)
        }

        private fun record(event: String, exception: RuntimeException, cache: SpringCache) {
            events += "$event:${exception.message}"
            causes += exception.cause
            this.cache = cache
        }
    }

    /**
     * 写入与失效都失败的缓存。
     */
    private class FailingCache(
        val putFailure: Exception = IllegalStateException("put failed"),
        val invalidateFailure: Exception = IllegalStateException("invalidate failed"),
    ) : Cache<Any, Any> {
        override val name: String = "user"

        override fun get(key: Any): Any? = null
        override fun get(key: Any, loader: CacheLoader<Any, Any>): Any? = loader.load(key)
        override fun getAll(keys: Collection<Any>): Map<Any, Any> = emptyMap()
        override fun lookup(key: Any): Lookup<Any> = Lookup.Absent(null)
        override fun put(key: Any, value: Any) = throw putFailure
        override fun put(key: Any, value: Any, ttl: Duration) = throw putFailure
        override fun lookupAll(keys: Collection<Any>): Map<Any, Lookup<Any>> =
            keys.associateWith { Lookup.Absent(null) }
        override fun putAll(entries: Map<Any, Any>) = throw putFailure
        override fun putAll(entries: Map<Any, Any>, ttl: Duration) = throw putFailure
        override fun invalidate(key: Any) = invalidateAll(listOf(key))
        override fun invalidateAll(keys: Collection<Any>) = throw invalidateFailure
        override fun async(): AsyncCache<Any, Any> = throw UnsupportedOperationException()
        override fun suspending(): SuspendingCache<Any, Any> = throw UnsupportedOperationException()
    }
}
