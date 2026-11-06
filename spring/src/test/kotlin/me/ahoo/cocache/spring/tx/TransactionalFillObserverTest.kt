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
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
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
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Function
import java.util.function.Predicate

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
     * 缺陷复现（C2）：事务内的读穿透把未提交的行写进 L1，回滚之后它留到 `ttl`。机制 1 在回滚时失效它；关闭时留下。
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
     * 回滚到 savepoint 时立即失效到目前为止的填充（外层事务结束之前它们对全集群可见）；回滚过 savepoint 的事务提交时
     * 再失效一次本事务的全部填充（#814）。
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
        users.inspect("5").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        users["5"].assert().isNull()
    }

    /**
     * 缺陷复现（#814）：另一个同步在它的 `savepointRollback` 中重新读取。Spring 先调用全部同步的 `savepointRollback`，
     * 再把数据库回滚到 savepoint：排在 CoCache 的同步之后的回调读穿透时，loader 读到的仍是即将回滚的值。本事务已经填充过的
     * key 之后门控（私有回源，不进入共享层），外层提交时再失效一次本事务的全部填充，之后读到已提交的数据。
     */
    @Test
    fun savepointRollbackCallbackRefillDoesNotSurviveTheOuterCommit() {
        val users = users()
        manager.write0("k", "committed")
        lateinit var seen: List<String?>
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                manager.write("k", "rolled-back")
                users["k"].assert().isEqualTo("rolled-back")
                seen = readOnSavepointRollback(users, "k")
                nested.setRollbackOnly()
            }
            manager.read("k").assert().isEqualTo("committed")
            users.inspect("k").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            users.inspect("k").local.assert().isNull()
        }
        seen.assert().containsExactly("rolled-back")
        users.gated().assert().isEqualTo(1)
        users["k"].assert().isEqualTo("committed")
    }

    /**
     * #814：回调排在 CoCache 的同步之前或之后，读取嵌套段内读过的 key（`k`）与没有读过的 key（`j`），外层提交之后都与已提交的
     * 数据一致。排在之后的回调对 `j` 的回源不门控（本事务没有填充过它），进入共享层，由外层提交时的失效清除。
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun savepointRollbackCallbacksInEitherOrder(callbackFirst: Boolean) {
        val users = users()
        manager.write0("k", "committed")
        manager.write0("j", "committed-j")
        lateinit var seen: List<String?>
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                if (callbackFirst) {
                    seen = readOnSavepointRollback(users, "k", "j")
                }
                manager.write("k", "rolled-back")
                manager.write("j", "rolled-back-j")
                users["k"].assert().isEqualTo("rolled-back")
                if (!callbackFirst) {
                    seen = readOnSavepointRollback(users, "k", "j")
                }
                TransactionSynchronizationManager.getSynchronizations().indexOfFirst { it is TransactionalOperations }
                    .assert().isEqualTo(if (callbackFirst) 1 else 0)
                nested.setRollbackOnly()
            }
        }
        seen.assert().containsExactly("rolled-back", "rolled-back-j")
        users["k"].assert().isEqualTo("committed")
        users["j"].assert().isEqualTo("committed-j")
    }

    /**
     * #814：两层 savepoint，内层回滚、外层段正常结束，之后另一个嵌套段再回滚一次；回调在每次回滚到 savepoint 时重新读取。
     * 最外层提交与回滚之后都读到已提交的数据：提交时保留外层段提交的写入（`a`），回滚的段内写入（`b`、`c`）不留下。
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun nestedSavepointRollbacksWithOuterCommitOrRollback(rollbackOuter: Boolean) {
        val users = users()
        manager.write0("a", "committed-a")
        manager.write0("b", "committed-b")
        tx.executeWithoutResult { outer ->
            nestedTx.executeWithoutResult {
                manager.write("a", "nested-a")
                users["a"].assert().isEqualTo("nested-a")
                nestedTx.executeWithoutResult { inner ->
                    manager.write("b", "inner-b")
                    users["b"].assert().isEqualTo("inner-b")
                    readOnSavepointRollback(users, "a", "b")
                    inner.setRollbackOnly()
                }
                manager.read("a").assert().isEqualTo("nested-a")
                manager.read("b").assert().isEqualTo("committed-b")
            }
            nestedTx.executeWithoutResult { again ->
                manager.write("c", "again-c")
                users["c"].assert().isEqualTo("again-c")
                readOnSavepointRollback(users, "c")
                again.setRollbackOnly()
            }
            if (rollbackOuter) {
                outer.setRollbackOnly()
            }
        }
        users["a"].assert().isEqualTo(if (rollbackOuter) "committed-a" else "nested-a")
        users["b"].assert().isEqualTo("committed-b")
        users["c"].assert().isNull()
    }

    /**
     * #814：三种 `fill-in-transaction` 各自的约定不变。`private`：嵌套段内与回调中的回源都是私有的；`registered-keys` 下已登记
     * 失效的 key 本来就门控；`allow` 不门控：回调的回源进入共享层，由外层提交时的失效清除。外层提交之后都读到已提交的数据。
     */
    @ParameterizedTest
    @EnumSource(FillInTransaction::class)
    fun savepointRollbackCallbackRefillUnderEachMode(mode: FillInTransaction) {
        val users = users(TransactionalFillObserver(true, mode))
        manager.write0("k", "committed")
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                manager.write("k", "rolled-back")
                if (mode == FillInTransaction.REGISTERED_KEYS) {
                    invalidator.invalidate(users, "k")
                }
                users["k"].assert().isEqualTo("rolled-back")
                readOnSavepointRollback(users, "k")
                nested.setRollbackOnly()
            }
            val remote = users.inspect("k").remote
            if (mode == FillInTransaction.ALLOW) {
                remote.value.assert().isEqualTo("rolled-back")
            } else {
                remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            }
        }
        users.gated().assert().isEqualTo(if (mode == FillInTransaction.ALLOW) 0 else 2)
        users["k"].assert().isEqualTo("committed")
    }

    /**
     * #814：回滚过 savepoint 的事务提交时先失效本事务的填充，再执行推迟的写入：推迟的写入是本事务提交的值，不被失效删掉。
     */
    @Test
    fun deferredWriteSurvivesTheFillInvalidationAfterASavepointRollback() {
        val users = users()
        tx.executeWithoutResult {
            manager.write("p", "draft")
            users["p"].assert().isEqualTo("draft")
            TransactionalOperations.current().afterCommit(users, "p") { users.put("p", "deferred") }
            nestedTx.executeWithoutResult { nested ->
                manager.write("q", "nested")
                users["q"].assert().isEqualTo("nested")
                nested.setRollbackOnly()
            }
        }
        users.inspect("p").remote.value.assert().isEqualTo("deferred")
        users.inspect("q").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
    }

    /**
     * 正常结束的嵌套段（释放 savepoint）不改变提交时的行为：填充保留。
     */
    @Test
    fun releasedSavepointKeepsTheFills() {
        val users = users()
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult {
                manager.write("n", "nested")
                users["n"].assert().isEqualTo("nested")
            }
        }
        users.inspect("n").remote.value.assert().isEqualTo("nested")
    }

    /**
     * 缺陷复现（#823）：`""` 是合法的业务 key。事务内为它产生的填充在回滚时失效：此前被运维入口的非空校验拒绝，
     * `afterCompletion` 记录异常之后返回，未提交的值留下。与非空 key 混在一次批量读取中同样如此。
     */
    @Test
    fun rollbackInvalidatesAnEmptyBusinessKey() {
        val users = users()
        manager.write0("", "committed")
        tx.executeWithoutResult { status ->
            manager.write("", "rolled-back")
            users[""].assert().isEqualTo("rolled-back")
            status.setRollbackOnly()
        }
        users[""].assert().isEqualTo("committed")

        manager.write0("a", "committed-a")
        users.invalidate("")
        tx.executeWithoutResult { status ->
            manager.write("", "batch")
            manager.write("a", "batch-a")
            users.getAll(listOf("", "a")).assert().isEqualTo(mapOf("" to "batch", "a" to "batch-a"))
            status.setRollbackOnly()
        }
        users.getAll(listOf("", "a")).assert().isEqualTo(mapOf("" to "committed", "a" to "committed-a"))
    }

    /**
     * 缺陷复现（#823）：回滚到 savepoint 时空业务 key 的填充同样立即失效，外层提交时再失效一次。
     */
    @Test
    fun savepointRollbackInvalidatesAnEmptyBusinessKey() {
        val users = users()
        manager.write0("", "committed")
        tx.executeWithoutResult {
            nestedTx.executeWithoutResult { nested ->
                manager.write("", "nested")
                users[""].assert().isEqualTo("nested")
                nested.setRollbackOnly()
            }
            users[""].assert().isEqualTo("committed")
        }
        users[""].assert().isEqualTo("committed")
    }

    /**
     * 缺陷复现（#823）：带版本的派生副本的空业务 key 同样在回滚时失效，并且保留版本栅栏（保留版本的淘汰，不是无条件删除）：
     * 回滚的版本之后，同一版本的迟到写入仍被拒绝，回源照常返回已提交的数据。
     */
    @Test
    fun versionedRollbackInvalidatesAnEmptyBusinessKeyAndKeepsTheFence() {
        val docs = runtime(TransactionalFillObserver()).create(
            versionedCacheSpec<String, String>("doc") {
                codec = StringCodec
                versionedLoader = CacheLoader { key ->
                    manager.read(key)?.let { Versioned(it.substringBefore('@'), it.substringAfter('@').toLong()) }
                }
            }
        )
        manager.write0("", "committed@1")
        tx.executeWithoutResult { status ->
            manager.write("", "rolled-back@2")
            docs[""].assert().isEqualTo("rolled-back")
            status.setRollbackOnly()
        }
        docs.putIfNewer("", "late", 2).assert().isEqualTo(VersionedPut.Stale(2))
        docs[""].assert().isEqualTo("committed")
    }

    /**
     * 注册一个在 `savepointRollback` 中按顺序读取 [keys] 的同步，返回它读到的值。
     */
    private fun readOnSavepointRollback(users: Cache<String, String>, vararg keys: String): List<String?> {
        val seen = mutableListOf<String?>()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun savepointRollback(savepoint: Any) {
                keys.forEach { seen += users[it] }
            }
        })
        return seen
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
        val users = users(TransactionalFillObserver(Predicate { true }, Function { modes.getValue(it) }))
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
     * 两个机制按缓存名取值：两者都关闭的缓存不进入事务（与 [FillObserver.NONE] 相同），回滚之后事务内写进 L1 的值留下；
     * 同一运行时中的其它缓存照常记录与失效。
     */
    @Test
    fun bothMechanismsOffForACacheBehaveLikeNone() {
        val observer = TransactionalFillObserver(
            Predicate { it == "user" },
            Function { if (it == "user") FillInTransaction.REGISTERED_KEYS else FillInTransaction.ALLOW }
        )
        tx.executeWithoutResult {
            observer.enter("user").assert().isNotNull()
            observer.enter("other").assert().isNull()
            // 不带缓存名的入口不区分缓存
            observer.enter().assert().isNotNull()
        }
        observer.enter("user").assert().isNull()
        observer.enter().assert().isNull()
        val runtime = runtime(observer)
        val users = runtime.create(userSpec())
        val others = runtime.create(
            cacheSpec<String, String>("other") {
                codec = StringCodec
                loader = CacheLoader { manager.read(it) }
            }
        )
        tx.executeWithoutResult { status ->
            manager.write("14", "draft")
            users["14"].assert().isEqualTo("draft")
            others["14"].assert().isEqualTo("draft")
            status.setRollbackOnly()
        }
        users.inspect("14").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        others.inspect("14").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
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
        TransactionalFillObserver().toString().assert().isEqualTo("TransactionalFillObserver")
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
