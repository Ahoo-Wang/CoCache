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
 * 一次 L1 读取的结果。
 */
sealed interface RemoteRead {
    /**
     * 命中。
     *
     * @param format 线格式版本；高于本节点所知的版本时，引擎既不回收也不写回
     * @param ttl 剩余寿命；`null` 表示永不过期
     * @param refreshGranted 是否取得了请求中的刷新令牌
     */
    data class Hit(
        val envelope: Envelope,
        val format: Int,
        val ttl: Duration?,
        val refreshGranted: Boolean = false
    ) : RemoteRead

    /**
     * 未命中。[leaseGranted] 表示是否原子地取得了请求中的 lease。
     */
    data class Miss(val leaseGranted: Boolean) : RemoteRead

    /**
     * 其它节点持有 lease、正在回源。
     */
    data class Leased(val remaining: Duration) : RemoteRead
}
