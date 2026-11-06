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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.FillKey
import org.apache.commons.logging.LogFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.IdentityHashMap

/**
 * 事务结束时失效失败的去向（[TransactionAwareSpringCache] 把它交给 `CacheErrorHandler`）。没有去向的失效失败按
 * [TransactionalInvalidator] 的规则聚合抛出。实现不应抛出。
 *
 * 不引用 spring-tx 的类型：spring-tx 不在类路径上时 [me.ahoo.cocache.spring.cache.CoCacheSpringCache] 仍要加载它。
 */
internal fun interface InvalidationFailureHandler {
    fun failed(error: Exception, keys: Collection<Any>)
}

/**
 * 一个事务内登记的 CoCache 操作（RFC 0005 §4.9）：待失效的 key（[TransactionalInvalidator]、`evictIfPresent`、
 * [TransactionAwareSpringCache.evict]）与推迟到提交之后的写入（[TransactionAwareSpringCache] 的 `put` 与 `clear`）。
 *
 * 每个事务一份，作为事务自己的同步注册，不按 [TransactionalInvalidator] 实例划分：同一事务内经不同入口登记的失效与写入
 * 在同一个同步中处理，写入与失效之间的先后因此可以比较。在当前事务自己的同步列表中查找，不用线程绑定的资源：
 * `REQUIRES_NEW` 挂起外层事务时只挂起同步列表，资源仍然绑定，内层登记的操作会混进外层、随外层的结束才执行。
 *
 * - **提交**：先失效全部登记的 key（同一缓存的 key 合并为一次 [Cache.invalidateAll]，去重并保持登记顺序），
 *   再按登记顺序执行写入。写入之后对同一缓存、同一 key 登记的失效**取代**它（写入不执行）：先 `@CachePut` 再
 *   `@CacheEvict` 的结果是失效，否则提交之后写回的是被删除之前的值；写入之前登记的失效不影响它。
 * - **回滚或提交结果未知**：只失效（同步 API 的 loader 在调用方线程上参与事务，事务内的读穿透可能已经把未提交的数据写入 L1），
 *   写入丢弃。
 * - **回滚到 savepoint**（`NESTED`、编程式 savepoint）：立即失效到目前为止登记的全部 key，登记保留，事务结束时照常再失效一次；
 *   丢弃 savepoint 之后登记的写入。savepoint 之后登记的失效保留，多余的失效无害。不抛出：Spring 在回滚数据库到 savepoint
 *   之前调用它，抛出会跳过数据库的回滚。
 * - **迟到的登记不丢失**：本事务已经完成（[complete]）之后（其它同步的 `afterCommit` 中）登记的失效与写入立即执行，失效失败与
 *   正常登记一样交给去向，没有去向时抛给调用方；本同步第一次注册发生在其它同步的 `afterCommit` 中（Spring 不再对它调用
 *   `afterCommit`），或排在前面的同步的 `afterCommit` 抛出时，在 `afterCompletion` 中按提交执行。`afterCompletion` 中同步已经清除，
 *   调用方直接执行，不会登记到这里。
 * - 一个缓存失败不影响其余缓存与写入。有去向（[InvalidationFailureHandler]）的失败交给去向；其余在全部执行之后抛出第一个，
 *   其它作为它的 suppressed：`afterCommit` 中抛给调用方，`afterCompletion` 中由 Spring 记录日志。
 *
 * 写事务内的回源（RFC 0005 §4.9，经 [TransactionalFillObserver]）：
 * - **机制 1**：数据源在本事务内产生、进入共享层的 key（[filled]）在回滚、提交结果未知时于登记的失效之后失效，回滚到 savepoint 时
 *   与登记的 key 一起立即失效；没有回滚过 savepoint 的提交什么也不做（读到的就是本事务提交的数据）。
 * - **回滚过 savepoint 的事务**（[savepointRolledBack]）：提交时同样失效本事务的全部填充，回滚到 savepoint 时已经记录的填充
 *   （[rolledBack]）在本事务之后与登记失效的 key 一样门控（[isGated]）。Spring 先调用全部同步的 `savepointRollback`，再回滚数据库：
 *   排在本同步之后的同步在它的 `savepointRollback` 中读取时，loader 读到的仍是即将回滚的数据，这样的填充无法与之后的填充区分，
 *   所以提交时一并失效；门控让 `registered-keys` 下这样的读取对已经填充过的 key 不进入共享层。
 * - **机制 2**：登记失效的 key 记入 [registered]，并立即在本节点递增戳、淘汰本地副本（[FillKey.evictLocal]），
 *   `registered-keys` 下本事务之后对它们的回源是私有的（[isGated]）。
 *
 * 只在事务所在的线程上使用。CoCache 的缓存按 (keyspace, 编码 key) 匹配（S24，[FillKey]）：同一个 key 经不同的 [Cache] 对象
 * （接口代理与引擎）登记时同样相互取代、同样进入门控；其它 [Cache] 实现按对象身份匹配。
 */
internal class TransactionalOperations private constructor() : TransactionSynchronization {

    private data class Target(val cache: Cache<*, *>, val onFailure: InvalidationFailureHandler?)

    private data class Entry(val cache: Cache<*, *>, val key: Any)

    /**
     * @param entry [entryOf] 的结果；`clear` 为 `null`
     */
    private class Write(val entry: Any?, val sequence: Int, val action: Runnable)

    private val keysByTarget = LinkedHashMap<Target, LinkedHashSet<Any>>()
    private val writes = ArrayList<Write>()

    /**
     * 每个 (缓存, key) 最近一次登记失效时的序号：序号更小的写入被它取代。
     */
    private val invalidatedAt = HashMap<Any, Int>()

    /**
     * 本事务登记失效的 key（机制 2 的门控）。
     */
    private val registered = HashSet<FillKey>()

    /**
     * 数据源在本事务内产生、进入共享层的 key（机制 1）。
     */
    private val filled = LinkedHashSet<FillKey>()

    /**
     * 回滚到 savepoint 时已经记录的填充：本事务之后与登记失效的 key 一样门控（[isGated]）。
     */
    private val rolledBack = HashSet<FillKey>()

    /**
     * 本事务回滚过 savepoint：提交时同样失效全部填充（[complete]）。
     */
    private var savepointRolledBack = false

    /**
     * savepoint → 创建它时 [writes] 的长度。
     */
    private val savepoints = IdentityHashMap<Any, Int>()
    private var sequence = 0

    /**
     * 已经完成：之后登记的操作不会再有回调执行，立即执行。
     */
    private var completed = false

    /**
     * 登记在事务结束之后失效 [keys]；失败交给 [onFailure]，为 `null` 时聚合抛出。本事务已经完成时立即失效，失败同样交给
     * [onFailure]，为 `null` 时抛给调用方。
     */
    fun invalidate(cache: Cache<*, *>, keys: Collection<Any>, onFailure: InvalidationFailureHandler?) {
        if (completed) {
            invalidateTarget(Target(cache, onFailure), keys)?.let { throw it }
            return
        }
        val current = ++sequence
        for (key in keys) {
            val fillKey = FillKey.of(cache, key)
            invalidatedAt[fillKey ?: Entry(cache, key)] = current
            if (fillKey != null && registered.add(fillKey)) {
                fillKey.evictLocal()
            }
        }
        keysByTarget.getOrPut(Target(cache, onFailure)) { LinkedHashSet() }.addAll(keys)
    }

    /**
     * `registered-keys` 的门控：[key] 已在本事务中登记失效，或在本事务回滚到 savepoint 时已经填充过。
     */
    fun isGated(key: FillKey): Boolean = key in registered || key in rolledBack

    /**
     * 机制 1：数据源在本事务内为 [key] 产生的值即将进入共享层。本事务已经完成时什么也不做：之后读到的是已提交的数据。
     */
    fun filled(key: FillKey) {
        if (!completed) {
            filled += key
        }
    }

    /**
     * 登记提交之后执行的写入。[key] 为 `null` 的写入（`clear`）不会被失效取代。[action] 自己处理失败，不应抛出。
     * 本事务已经完成时立即执行。
     */
    fun afterCommit(cache: Cache<*, *>, key: Any?, action: Runnable) {
        if (completed) {
            action.run()
            return
        }
        writes += Write(key?.let { FillKey.of(cache, it) ?: Entry(cache, it) }, ++sequence, action)
    }

    override fun afterCommit() {
        complete(committed = true)
    }

    /**
     * 兜底，已经完成时什么也不做：回滚或提交结果未知只失效；提交（`afterCommit` 没有执行，见类注释）照常执行写入。
     */
    override fun afterCompletion(status: Int) {
        complete(committed = status == TransactionSynchronization.STATUS_COMMITTED)
    }

    override fun savepoint(savepoint: Any) {
        savepoints[savepoint] = writes.size
    }

    /**
     * 不认识的 savepoint 创建于本同步注册之前：已登记的写入都在回滚的那一段内。到目前为止的填充之后门控，事务提交时再失效一次
     * （见类注释：排在后面的同步在数据库回滚之前的读取）。
     */
    @Suppress("TooGenericExceptionCaught")
    override fun savepointRollback(savepoint: Any) {
        savepointRolledBack = true
        rolledBack += filled
        invalidateRegistered()
        try {
            FillKey.invalidateAll(unregisteredFills())
        } catch (e: Exception) {
            log.warn(
                "Failed to invalidate the keys loaded in this transaction on savepoint rollback; " +
                    "they are invalidated again when the transaction completes.",
                e
            )
        }
        val mark = savepoints[savepoint] ?: 0
        if (mark < writes.size) {
            writes.subList(mark, writes.size).clear()
        }
    }

    /**
     * 只执行一次：先失效登记的 key；回滚、提交结果未知或回滚过 savepoint 时再失效本事务内的填充（机制 1）；提交时最后执行没有被
     * 取代的写入（推迟的写入是本事务提交的值，不被填充的失效删掉）；最后抛出没有去向的失效失败。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun complete(committed: Boolean) {
        if (completed) {
            return
        }
        completed = true
        var failure = invalidateTargets()
        if (!committed || savepointRolledBack) {
            try {
                FillKey.invalidateAll(unregisteredFills())
            } catch (e: Exception) {
                failure = failure?.apply { addSuppressed(e) } ?: e
            }
        }
        if (committed) {
            for (write in writes) {
                val entry = write.entry
                if (entry == null || (invalidatedAt[entry] ?: 0) < write.sequence) {
                    write.action.run()
                }
            }
        }
        failure?.let { throw it }
    }

    /**
     * 逐个缓存失效登记的 key（[invalidateTarget]）：没有去向的失败返回第一个，其它作为它的 suppressed。
     */
    private fun invalidateTargets(): Exception? {
        var failure: Exception? = null
        for ((target, keys) in keysByTarget) {
            val error = invalidateTarget(target, keys) ?: continue
            val first = failure
            if (first == null) failure = error else first.addSuppressed(error)
        }
        return failure
    }

    /**
     * 失效一个去向的 [keys]：失败交给去向，没有去向时返回它。
     */
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun invalidateTarget(target: Target, keys: Collection<Any>): Exception? {
        try {
            (target.cache as Cache<Any, *>).invalidateAll(keys)
        } catch (e: Exception) {
            val onFailure = target.onFailure ?: return e
            onFailure.failed(e, keys)
        }
        return null
    }

    /**
     * 机制 1 要失效的 key：已登记失效的 key 刚刚随登记失效过，跳过。
     */
    private fun unregisteredFills(): List<FillKey> = filled.filterNot { it in registered }

    /**
     * 失效到目前为止登记的全部 key，不标记为已完成。不抛出：一个缓存失败只记录日志，事务结束时的失效兜底并照常报告失败。
     */
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun invalidateRegistered() {
        for ((target, keys) in keysByTarget) {
            try {
                (target.cache as Cache<Any, *>).invalidateAll(keys)
            } catch (e: Exception) {
                log.warn(
                    "Failed to invalidate ${keys.size} key(s) of cache [${target.cache.name}] on savepoint rollback; " +
                        "they are invalidated again when the transaction completes.",
                    e
                )
            }
        }
    }

    companion object {
        private val log = LogFactory.getLog(TransactionalOperations::class.java)

        /**
         * 当前事务的登记：在当前事务自己的同步列表中查找，第一次登记时注册。调用方保证事务同步已激活。
         */
        fun current(): TransactionalOperations {
            TransactionSynchronizationManager.getSynchronizations()
                .firstOrNull { it is TransactionalOperations }
                ?.let { return it as TransactionalOperations }
            val operations = TransactionalOperations()
            TransactionSynchronizationManager.registerSynchronization(operations)
            return operations
        }
    }
}
