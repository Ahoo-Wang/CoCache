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

/**
 * 转发全部操作与能力访问器的 [L1Store]（RFC 0005 §4.3）：装饰器继承它，只覆盖要拦截的部分。
 *
 * 能力访问器原样返回被装饰者的能力面，所以能力面上的操作不经过装饰器。要拦截某个能力的操作，就覆盖对应的访问器，
 * 返回包了一层的 [ForwardingConditionalL1Store]、[ForwardingVersionedL1Store] 或 [ForwardingSetL1Store]；
 * 被装饰者的访问器为 `null` 时也返回 `null`，不凭空添加能力。包装在构造时创建一次，访问器每次返回同一个对象（[L1Store] 的约定）：
 *
 * ```kotlin
 * class CountingStore(delegate: L1Store) : ForwardingL1Store(delegate) {
 *     val conditionalWrites = AtomicInteger()
 *
 *     private val conditional = delegate.conditional()?.let { surface ->
 *         object : ForwardingConditionalL1Store(surface) {
 *             override suspend fun putIfMatch(
 *                 key: String,
 *                 expected: Digest?,
 *                 envelope: Envelope.Scalar,
 *                 ttl: Duration?,
 *                 frame: ByteArray
 *             ): L1ConditionalWrite {
 *                 conditionalWrites.incrementAndGet()
 *                 return super.putIfMatch(key, expected, envelope, ttl, frame)
 *             }
 *         }
 *     }
 *
 *     override fun conditional(): ConditionalL1Store? = conditional
 * }
 * ```
 *
 * @param delegate 被装饰的存储
 */
@Suppress("TooManyFunctions")
abstract class ForwardingL1Store(protected val delegate: L1Store) : L1Store {
    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read =
        delegate.read(key, lease, refresh, timeout)

    override suspend fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): Map<String, L1Read> = delegate.readAll(keys, leases, timeout)

    override suspend fun fill(fill: Fill): L1Fill = delegate.fill(fill)

    override suspend fun fillAll(fills: List<Fill>): List<L1Fill> = delegate.fillAll(fills)

    override suspend fun release(key: String, token: Token) = delegate.release(key, token)

    override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean =
        delegate.reclaim(key, observed, lease)

    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) =
        delegate.put(key, envelope, ttl, frame)

    override suspend fun putAll(puts: List<Put>, frame: ByteArray) = delegate.putAll(puts, frame)

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) = delegate.invalidate(keys, frame)

    /**
     * 被装饰者的条件读写能力面（原样返回，不经过本装饰器）。要拦截时覆盖它，见类注释。
     */
    override fun conditional(): ConditionalL1Store? = delegate.conditional()

    /**
     * 被装饰者的按版本写入能力面（原样返回，不经过本装饰器）。要拦截时覆盖它，见类注释。
     */
    override fun versioned(): VersionedL1Store? = delegate.versioned()

    /**
     * 被装饰者的集合能力面（原样返回，不经过本装饰器）。要拦截时覆盖它，见类注释。
     */
    override fun sets(): SetL1Store? = delegate.sets()
}

/**
 * 转发全部操作的 [ConditionalL1Store]：拦截条件读写的装饰器继承它，由 [ForwardingL1Store.conditional] 返回。
 *
 * @param delegate 被装饰的能力面
 */
abstract class ForwardingConditionalL1Store(protected val delegate: ConditionalL1Store) : ConditionalL1Store {
    override suspend fun readIfChanged(
        key: String,
        known: Digest?,
        timeout: Duration?
    ): L1ConditionalRead = delegate.readIfChanged(key, known, timeout)

    override suspend fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): L1ConditionalWrite = delegate.putIfMatch(key, expected, envelope, ttl, frame)
}

/**
 * 转发全部操作的 [VersionedL1Store]：拦截按版本写入的装饰器继承它，由 [ForwardingL1Store.versioned] 返回。
 *
 * @param delegate 被装饰的能力面
 */
abstract class ForwardingVersionedL1Store(protected val delegate: VersionedL1Store) : VersionedL1Store {
    override suspend fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): L1VersionedWrite = delegate.putIfNewer(key, envelope, version, ttl, frame)

    override suspend fun evictKeepingVersion(
        key: String,
        ttl: Duration?,
        frame: ByteArray
    ): L1Eviction = delegate.evictKeepingVersion(key, ttl, frame)
}

/**
 * 转发全部操作的 [SetL1Store]：拦截集合成员增删的装饰器继承它，由 [ForwardingL1Store.sets] 返回。
 *
 * @param delegate 被装饰的能力面
 */
abstract class ForwardingSetL1Store(protected val delegate: SetL1Store) : SetL1Store {
    override suspend fun addMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = delegate.addMembers(key, members, ttl, frame)

    override suspend fun removeMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = delegate.removeMembers(key, members, ttl, frame)
}
