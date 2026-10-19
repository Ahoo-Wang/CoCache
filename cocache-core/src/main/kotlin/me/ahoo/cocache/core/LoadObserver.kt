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

package me.ahoo.cocache.core

import java.util.function.Supplier

/**
 * 回源观测 SPI（如链路追踪）：只包住回源调用与等待其它节点的 lease，L2 命中与 L1 读取都不经过它（G3）。
 *
 * 实现必须在调用方线程上同步执行 `block` 恰好一次，原样返回其结果、原样抛出其异常（异常透明）；
 * 不得把 key 写入观测数据（高基数，且可能是个人信息）。通过 [CacheRuntime.Builder.loadObserver] 设置，默认 [NOOP]。
 */
interface LoadObserver {
    /**
     * 观测一次操作。
     *
     * @param cacheName 缓存名
     * @param operation 操作类型
     * @param keyCount 本次操作涉及的 key 数（单 key 为 1）
     * @param block 被观测的操作
     */
    fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T

    companion object {
        /**
         * 什么也不做：直接执行 `block`。引擎识别它并跳过观测调用。
         */
        @JvmField
        val NOOP: LoadObserver = object : LoadObserver {
            override fun <T> observe(
                cacheName: String,
                operation: LoadOperation,
                keyCount: Int,
                block: Supplier<T>
            ): T = block.get()

            override fun toString(): String = "LoadObserver.NOOP"
        }
    }
}

/**
 * 被观测的操作类型；[value] 是低基数的标签值。
 */
enum class LoadOperation(val value: String) {
    /**
     * 未命中时的单 key 回源（含 `get(key, loader)` 与批量读取中的单 key 回退）。
     */
    LOAD("load"),

    /**
     * 批量读取的一次批量回源。
     */
    LOAD_ALL("loadAll"),

    /**
     * 提前刷新（refresh-ahead）的后台回源。
     */
    REFRESH("refresh"),

    /**
     * 其它节点持有 lease 时的等待（只在确实需要等待时发生）。
     */
    LEASE_WAIT("lease.wait"),
}
