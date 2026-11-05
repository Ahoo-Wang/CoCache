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

/**
 * 令牌保护的填充（[L1Store.fill]、[L1Store.fillAll]）的结果（RFC 0005 §4.3，round 3 K12）。
 *
 * `Boolean` 区分不了“已写入且版本前进”“已写入”“版本拒绝”：lease 占在带版本的内容上时，读取方读到的是没有值，并不知道版本。
 * 结果类型在 M3d 定下，之后的格式（RFC 0005 §4.7）不再改 SPI。
 */
sealed interface L1Fill {
    /**
     * 已写入。
     *
     * @property advanced 这次填充是一次写入，存储同时广播了失效（原子广播时）：L1 中原有带版本的值，填充的版本更大；
     * 或 L1 中是 `E`（保留版本的失效，填充的版本不小于它）。引擎据此按写入处理本节点（RFC 0005 §4.7）：递增戳、淘汰本地副本、
     * 不填充 L2，非原子广播时由引擎发布。L1 中原来没有版本（lease 占位、没有版本的值），或版本相等的续期时为 `false`。
     */
    data class Filled(val advanced: Boolean) : L1Fill

    /**
     * 没有写入。
     *
     * @property currentVersion 版本规则拒绝时为 key 中的版本（填充的版本更小或没有版本；存储已清零令牌。刷新路径据此保留现有副本，
     * RFC 0005 §4.7）；令牌不匹配时为 `null`。
     */
    data class Rejected(val currentVersion: Long?) : L1Fill
}
