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
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Function

/**
 * 写事务内的回源（RFC 0005 §4.9）：机制 1 在回滚、提交结果未知与回滚到 savepoint 时失效事务内的填充；机制 2 按
 * `fill-in-transaction` 让回源私有；只读事务与事务之外不受影响。loader 经 [InMemoryTransactionManager] 读取，事务内看得到未提交的写入。
 */
class TransactionalFillObserverTest {
    private val manager = InMemoryTransactionManager()
    private val tx = TransactionTemplate(manager)
    private val readOnlyTx = TransactionTemplate(manager).apply { isReadOnly = true }
    private val nestedTx = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }
    private val invalidator = TransactionalInvalidator()
    private val backend = HookedBackend(InMemoryBackend())
    private val runtimes = mutableListOf<CacheRuntime>()

    interface UserCache : Cache<String, String>

    @AfterEach
    fun destroy() = runtimes.forEach { it.close() }

    private fun runtime(observer: FillObserver): CacheRuntime =
        CacheRuntime.builder().backend(backend).fillObserver(observer).build().also { runtimes += it }

    private fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
        codec = StringCodec
        loader = CacheLoader { manager.read(it) }
    }

    private fun users(observer: FillObserver = TransactionalFillObserver()): Cache<String, String> =
        runtime(observer).create(userSpec())

    /**
     * 接口式声明：引擎与它的接口代理。
     */
    private fun usersWithProxy(): Pair<Cache<String, String>, UserCache> {
        val proxy = CoCacheFactory(runtime(TransactionalFillObserver())).create(UserCache::class.java, userSpec())
        @Suppress("UNCHECKED_CAST")
        return (requireNotNull(CacheProxies.targetOf(proxy)) as Cache<String, String>) to proxy
    }

    private fun Cache<String, String>.inspect(key: String): KeyInspection =
        requireNotNull(runtimes.last().admin(name)).inspect(key)

    private fun Cache<String, String>.gated(): Long = requireNotNull(runtimes.last().admin(name)).metrics.fillsGated

    /**
     * 缺陷复现（C2）：事务内的读穿透把未提交的行写进 L1，回滚之后它留到 `ttl`。机制 1 在回滚时失效它；关闭时（6.x 的行为）留下。
     */
    @Test
    fun rollbackInvalidatesTheFillsOfTheTransaction() {
        val users = users()
        tx.executeWithoutResult { status ->
            manager.write("1", "draft")
            users["1"].assert().isEqualTo("draft")
            users.inspect("1").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
            status.setRollbackOnly()
        }
        users.inspect("1").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        users["1"].assert().isNull()
    }

    @Test
    fun withoutTheObserverARolledBackFillStays() {
        val users = users(FillObserver.NONE)
        tx.executeWithoutResult { status ->
            manager.write("1", "draft")
            users["1"].assert().isEqualTo("draft")
            status.setRollbackOnly()
        }
        users["1"].assert().isEqualTo("draft")
    }

    /**
     * 提交时什么也不做：读到的就是本事务提交的数据。
     */
    @Test
    fun commitKeepsTheFills() {
        val users = users()
        tx.executeWithoutResult {
            manager.write("2", "saved")
            users["2"].assert().isEqualTo("saved")
        }
        users.inspect("2").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        users["2"].assert().isEqualTo("saved")
    }

    /**
     * S15：L1 读失败时只写本节点 L2 的降级填充同样记录，回滚时淘汰。
     */
    @Test
    fun rollbackInvalidatesADegradedFill() {
        val users = users()
        tx.executeWithoutResult { status ->
            manager.write("3", "draft")
            backend.failAll = true
            users["3"].assert().isEqualTo("draft")
            backend.failAll = false
            users.inspect("3").local?.state.assert().isEqualTo(KeyInspection.LocalState.VALUE)
            status.setRollbackOnly()
        }
        users.inspect("3").local.assert().isNull()
        users["3"].assert().isNull()
    }

    /**
     * 提交结果未知（`STATUS_UNKNOWN`）与回滚一样失效。
     */
    @Test
    fun unknownCommitOutcomeInvalidatesTheFills() {
        val users = users()
        manager.failCommit = true
        runCatching {
            tx.executeWithoutResult {
                manager.write("4", "maybe")
                users["4"].assert().isEqualTo("maybe")
            }
        }.exceptionOrNull().assert().isInstanceOf(TransactionSystemException::class.java)
        manager.failCommit = false
        users.inspect("4").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
    }

    /**
     * 回滚到 savepoint 时立即失效到目前为止的填充（外层事务结束之前它们对全集群可见）；外层提交时不再失效。
     */
    @Test
    fun savepointRollbackInvalidatesTheFillsBeforeTheOuterCommit() {
        val users = users()
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                manager.write("5", "nested")
                users["5"].assert().isEqualTo("nested")
                nested.setRollbackOnly()
            }
            users.inspect("5").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            users["5"].assert().isNull()
        }
        users.inspect("5").remote.state.assert().isEqualTo(KeyInspection.RemoteState.ABSENT)
    }

    /**
     * 机制 2（默认 `registered-keys`）：登记失效之后同一事务内的回源是私有的，不写 L1 与 L2；登记立即淘汰本节点的副本，
     * 本事务接着读到自己的写入。经接口代理登记、经引擎读取同样匹配（S24）。提交之后照常失效、照常填充。
     */
    @Test
    fun registeredKeysLoadPrivately() {
        val (users, proxy) = usersWithProxy()
        manager.write0("6", "old")
        users["6"].assert().isEqualTo("old")
        tx.executeWithoutResult {
            manager.write("6", "new")
            invalidator.invalidate(proxy, "6")
            invalidator.invalidate(users, "6")
            users.inspect("6").local.assert().isNull()
            users["6"].assert().isEqualTo("new")
            users.inspect("6").remote.value.assert().isEqualTo("old")
            users.inspect("6").local.assert().isNull()
            users.gated().assert().isEqualTo(1)
            // 没有登记的 key 照常经 L1
            manager.write("7", "other")
            users["7"].assert().isEqualTo("other")
            users.inspect("7").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        }
        users.inspect("6").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        users["6"].assert().isEqualTo("new")
        users.gated().assert().isEqualTo(1)
    }

    /**
     * `private`：写事务内的全部回源都是私有的；`allow`：不门控，仍受机制 1 保护。取值按缓存名给出（按缓存分层的接缝）。
     */
    @Test
    fun privateAndAllowModesPerCache() {
        val modes = mutableMapOf("user" to FillInTransaction.PRIVATE)
        val users = users(TransactionalFillObserver(true, Function { modes.getValue(it) }))
        tx.executeWithoutResult {
            manager.write("8", "draft")
            users["8"].assert().isEqualTo("draft")
            users.inspect("8").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        }
        users.gated().assert().isEqualTo(1)

        modes["user"] = FillInTransaction.ALLOW
        tx.executeWithoutResult { status ->
            manager.write("9", "draft")
            invalidator.invalidate(users, "9")
            users["9"].assert().isEqualTo("draft")
            users.inspect("9").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
            status.setRollbackOnly()
        }
        users.inspect("9").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        users.gated().assert().isEqualTo(1)
    }

    /**
     * 关闭机制 1 时只门控、不记录：回滚之后事务内写进 L1 的值留下。
     */
    @Test
    fun withoutRollbackInvalidationOnlyGates() {
        val users = users(TransactionalFillObserver(invalidateFillsOnRollback = false))
        tx.executeWithoutResult { status ->
            manager.write("10", "draft")
            users["10"].assert().isEqualTo("draft")
            status.setRollbackOnly()
        }
        users.inspect("10").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
    }

    /**
     * 只读事务与事务之外不门控、不记录，也不注册事务同步。
     */
    @Test
    fun readOnlyTransactionsAndNoTransactionAreNotObserved() {
        val users = users(TransactionalFillObserver(true, FillInTransaction.PRIVATE))
        manager.write0("11", "committed")
        readOnlyTx.executeWithoutResult {
            users["11"].assert().isEqualTo("committed")
            TransactionSynchronizationManager.getSynchronizations().assert().isEmpty()
        }
        users.inspect("11").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        // 同步已激活、没有实际事务（SUPPORTS）
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS }
            .executeWithoutResult {
                users["13"].assert().isNull()
                TransactionSynchronizationManager.getSynchronizations().assert().isEmpty()
            }
        manager.write0("12", "committed")
        users["12"].assert().isEqualTo("committed")
        users.gated().assert().isEqualTo(0)
        TransactionalFillObserver().toString().assert().contains("invalidateFillsOnRollback=true")
    }

    /**
     * 事务结束时一个缓存失效失败不影响其余：抛出第一个异常、其余作为 suppressed，Spring 记录 `afterCompletion` 抛出的它；
     * 回滚到 savepoint 时失效填充失败只记录告警。
     */
    @Test
    fun invalidationFailuresAreAggregatedOrLogged() {
        val strict = runtime(TransactionalFillObserver()).create(
            cacheSpec<String, String>("strict") {
                codec = StringCodec
                loader = CacheLoader { manager.read(it) }
                policy = CachePolicy(invalidateFailure = InvalidateFailureMode.THROW)
            }
        )
        val savepointLogs = captureLogs(TransactionalOperations::class.java) {
            tx.executeWithoutResult {
                nestedTx.executeWithoutResult { nested ->
                    manager.write("a", "nested")
                    strict["a"].assert().isEqualTo("nested")
                    backend.failAll = true
                    nested.setRollbackOnly()
                }
                backend.failAll = false
            }
        }
        savepointLogs.any { it.level == Level.WARN && it.formattedMessage.contains("savepoint rollback") }
            .assert().withFailMessage { "savepoint failure not logged: $savepointLogs" }.isTrue()

        val completionLogs = captureLogs(TransactionSynchronizationUtils::class.java) {
            tx.executeWithoutResult { status ->
                manager.write("b", "draft")
                strict["b"].assert().isEqualTo("draft")
                invalidator.invalidate(strict, "c")
                backend.failAll = true
                status.setRollbackOnly()
            }
            backend.failAll = false
        }
        val failure = requireNotNull(completionLogs.single { it.level == Level.ERROR }.throwableProxy)
        failure.suppressed.size.assert().isEqualTo(1)
    }

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
     * 本事务完成之后（其它同步的 `afterCommit` 中）的回源读到已提交的数据，不再记录。
     */
    @Test
    fun fillsAfterCompletionAreNotRecorded() {
        val users = users()
        tx.executeWithoutResult {
            manager.write("13", "saved")
            users["13"]
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    users["14"].assert().isNull()
                }
            })
        }
        users.inspect("14").remote.state.assert().isEqualTo(KeyInspection.RemoteState.ABSENT)
    }

    /**
     * S24：经引擎推迟的写入被经接口代理登记的同一 key 的失效取代。
     */
    @Test
    fun aWriteIsSupersededByAnInvalidationThroughAnotherObject() {
        val (users, proxy) = usersWithProxy()
        var written = false
        tx.executeWithoutResult {
            TransactionalOperations.current().afterCommit(users, "15") { written = true }
            invalidator.invalidate(proxy, "15")
        }
        written.assert().isFalse()
    }

    /**
     * 写入已提交的数据（事务之外）。
     */
    private fun InMemoryTransactionManager.write0(key: String, value: String) {
        tx.executeWithoutResult { write(key, value) }
    }
}
