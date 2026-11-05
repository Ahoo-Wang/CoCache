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

/**
 * 带版本的值（按版本有序的写入）：[VersionedCache.getVersioned] / [VersionedStore.getVersioned] 的结果（`value` 不为 `null`），
 * 也是带版本的数据源（`VersionedCacheSpec.versionedLoader`，类型为 `Versioned<V?>`）的返回值。
 *
 * 版本是调用方的单调序号（如事件溯源聚合的版本、提交序号），按有符号 64 位整数比较。
 * 没有版本的内容（不带版本的负缓存、外部写入的值）的版本是 [NO_VERSION]，排在任何版本之前。
 *
 * 带版本的数据源以 `Versioned(null, n)` 表达删除（“在版本 n 时不存在”，如已删除的聚合）：回源写入与
 * [VersionedCache.invalidateIfNewer] 相同的墓碑，挡住不更新的版本。返回 `null` 表示数据源中从来没有这个 key，写入没有版本的负缓存；
 * 它落在带版本的内容上（如 `evict` 留下的 `E`）时被拒绝（K16，见 [VersionedCache]）。
 *
 * @param value 值；只有带版本的数据源的返回值可以为 `null`（带版本的不存在）
 * @param version 这份值的版本；[NO_VERSION] 表示没有版本
 */
data class Versioned<out V>(val value: V, val version: Long) {
    companion object {
        /**
         * “没有版本”：`Long.MIN_VALUE`，小于任何版本，因此任何带版本的写入都会覆盖没有版本的内容。
         * 不能作为 `putIfNewer` / `invalidateIfNewer` / `deleteIfNewer` 的版本。
         */
        const val NO_VERSION: Long = Long.MIN_VALUE
    }
}

/**
 * 按版本写入（[VersionedCache.putIfNewer]、[VersionedCache.invalidateIfNewer]、[VersionedStore.putIfNewer]、
 * [VersionedStore.deleteIfNewer]）的结果。
 */
sealed interface VersionedPut {
    /**
     * 已写入 L1 并广播失效：给出的版本大于 L1 中的版本（没有版本的内容、不存在、lease 占位都排在任何版本之前）。
     */
    data object Written : VersionedPut

    /**
     * 没有写入，也没有改变到期时间：L1 中已有不更旧的版本。
     * `current` 等于给出的版本时是重复投递（幂等，仍广播一次），大于时是迟到的旧版本（不广播）。
     *
     * @param current L1 中的版本
     */
    data class Stale(val current: Long) : VersionedPut
}
