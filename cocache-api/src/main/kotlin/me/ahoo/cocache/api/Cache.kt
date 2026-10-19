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

package me.ahoo.cocache.api

import java.time.Duration

/**
 * 两级一致性缓存：进程内 L2 → 共享 L1 → 数据源。
 *
 * 一致性目标见 RFC 0003 §1.2：陈旧度有上界（G1）、失效不被吞没（G2）、命中路径便宜（G3）。
 *
 * @param K 业务 key
 * @param V 缓存值
 */
interface Cache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 读穿透：L2 → L1 → 数据源。不存在（含负缓存）返回 `null`。
     */
    operator fun get(key: K): V?

    /**
     * 读穿透，未命中时用 [loader] 回源（代替缓存声明中的数据源）。
     *
     * 与 [get] 走同一条受 lease 与失效戳保护的路径：回源结果只能凭 lease 写回 L1，期间发生的失效不会被吞没。
     * 供 Spring `@Cacheable(sync = true)` 等“调用方提供加载逻辑”的场景使用，避免“读未命中 → 加载 → 无条件写入”的竞态。
     */
    fun get(key: K, loader: CacheLoader<K, V>): V?

    /**
     * 批量读穿透：L1 一次批量读取、数据源一次批量加载，并与并发的单 key 读取共享在途加载。
     * 结果只包含存在的 key。
     */
    fun getAll(keys: Collection<K>): Map<K, V>

    /**
     * 需要区分“确认不存在”（负缓存）时使用。
     */
    fun lookup(key: K): Lookup<V>

    /**
     * 写穿透到 L1 并广播失效，按策略决定到期时间；原子地使所有在途填充失效。
     *
     * 写入不填充本地 L2：下一次读取从 L1 填充，保证 L2 与 L1 的写入顺序一致。
     *
     * @throws CacheWriteException L1 写入失败（结果可能未知，操作幂等，可安全重试）
     */
    fun put(key: K, value: V)

    /**
     * 以指定时长写入，语义同 [put]。
     */
    fun put(key: K, value: V, ttl: Duration)

    /**
     * 失效两级缓存并广播。必须在数据源提交之后调用（事务内应登记到提交之后）。
     *
     * L1 写入失败时默认进入重试队列而不抛出（见 RFC 0003 §4.13）。
     */
    fun invalidate(key: K)

    /**
     * 批量失效，语义同 [invalidate]。
     */
    fun invalidateAll(keys: Collection<K>)
}
