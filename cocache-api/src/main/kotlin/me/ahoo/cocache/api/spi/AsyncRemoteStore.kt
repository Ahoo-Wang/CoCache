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

package me.ahoo.cocache.api.spi

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 原生异步的 L1 协议（RFC 0004 §4）：语义与 [RemoteStore] 的同名方法逐一相同（原子性、到期时间重设、广播、令牌）。
 *
 * 实现约定：
 * - 返回的 future 可以在 I/O 线程上完成（引擎会切换线程）；失败时以与同步方法相同的异常类型异常完成，不得同步抛出。
 * - 必须自行限定每个操作的耗时（如命令超时），不得返回永不完成的 future。
 *
 * 不实现本接口的后端仍然可用：引擎把同步方法放到阻塞执行器上执行。
 */
interface AsyncRemoteStore : RemoteStore {
    /**
     * 异步的 [RemoteStore.read]：读取，按请求原子地取得 lease 或刷新令牌。
     */
    fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead>

    /**
     * 异步的 [RemoteStore.readAll]：批量读取，只为 leases 中的 key 申请 lease。
     */
    fun readAllAsync(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>
    ): CompletableFuture<Map<String, RemoteRead>>

    /**
     * 异步的 [RemoteStore.fill]：令牌匹配时写入，以是否写入完成。
     */
    fun fillAsync(fill: Fill): CompletableFuture<Boolean>

    /**
     * 异步的 [RemoteStore.fillAll]：批量填充，结果与 fills 一一对应。
     */
    fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>>

    /**
     * 异步的 [RemoteStore.release]：令牌匹配时释放。
     */
    fun releaseAsync(key: String, token: Token): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.put]：整体覆盖写入并广播。
     */
    fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.putAll]：批量整体覆盖写入并广播。
     */
    fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.invalidate]：删除并广播。
     */
    fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.reclaim]：摘要匹配时条件回收为 lease，以是否回收完成。
     */
    fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean>

    /**
     * 异步的 [RemoteStore.addMembers]：原子地加入集合成员并广播。
     */
    fun addMembersAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.removeMembers]：原子地移除集合成员并广播。
     */
    fun removeMembersAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Void?>

    /**
     * 异步的 [RemoteStore.addMembersIfReadable]：不能当作本协议集合的内容不修改、不广播，以其格式版本完成；写入时以 `null` 完成。
     *
     * 默认实现先 [readAsync] 再 [addMembersAsync]（不原子，认不出更高的格式版本）；实现应当覆盖它。
     */
    fun addMembersIfReadableAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?> = readAsync(key, null, null).thenCompose { read ->
        val unreadable = unreadableSetFormat(read)
        if (unreadable != null) {
            CompletableFuture.completedFuture(unreadable)
        } else {
            addMembersAsync(key, members, ttl, frame).thenApply { null }
        }
    }

    /**
     * 异步的 [RemoteStore.removeMembersIfReadable]：不能当作本协议集合的内容不修改、不广播，以其格式版本完成；执行时以 `null` 完成。
     *
     * 默认实现先 [readAsync] 再 [removeMembersAsync]（不原子，认不出更高的格式版本）；实现应当覆盖它。
     */
    fun removeMembersIfReadableAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?> = readAsync(key, null, null).thenCompose { read ->
        val unreadable = unreadableSetFormat(read)
        if (unreadable != null) {
            CompletableFuture.completedFuture(unreadable)
        } else {
            removeMembersAsync(key, members, ttl, frame).thenApply { null }
        }
    }

    /**
     * 异步的 [RemoteStore.readIfChanged]：摘要相同时以 [RemoteConditionalRead.Unchanged] 完成，不返回正文。
     *
     * 默认实现调用 [readAsync]（总是传输正文）；实现应当覆盖它。
     */
    fun readIfChangedAsync(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead> =
        readAsync(key, null, null).thenApply { conditionalReadOf(it, known) }

    /**
     * 异步的 [RemoteStore.putIfMatch]：当前内容符合期望时写入并广播，以 [RemoteConditionalWrite.Written] 完成；否则不修改、不广播。
     *
     * 默认实现以 [UnsupportedOperationException] 完成（理由同 [RemoteStore.putIfMatch]）。只覆盖了同步方法的原生异步存储也是如此：
     * 默认实现不能在调用方线程上阻塞地调用同步方法，支持条件写入的原生异步存储必须同时覆盖两者。
     */
    @Suppress("LongParameterList")
    fun putIfMatchAsync(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray,
        foreignAsAbsent: Boolean
    ): CompletableFuture<RemoteConditionalWrite> = CompletableFuture.failedFuture(
        UnsupportedOperationException("${javaClass.name} does not support putIfMatchAsync().")
    )

    /**
     * 异步的 [RemoteStore.putIfNewer]：版本更新时写入并广播，以 [RemoteVersionedWrite.Written] 完成；否则不修改、不广播。
     *
     * 默认实现以 [UnsupportedOperationException] 完成（理由同 [RemoteStore.putIfNewer]，不在调用方线程上阻塞地调用同步方法）：
     * 支持版本的原生异步存储必须同时覆盖两者。
     */
    @Suppress("LongParameterList")
    fun putIfNewerAsync(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray,
        foreignAsAbsent: Boolean
    ): CompletableFuture<RemoteVersionedWrite> = CompletableFuture.failedFuture(
        UnsupportedOperationException("${javaClass.name} does not support putIfNewerAsync().")
    )
}

/**
 * 原生异步的失效发布（非原子广播的后端）：语义同 [InvalidationChannel.publish]，约定同 [AsyncRemoteStore]。
 */
interface AsyncInvalidationChannel : InvalidationChannel {
    /**
     * 异步的 [InvalidationChannel.publish]：发布失效帧。
     */
    fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?>
}
