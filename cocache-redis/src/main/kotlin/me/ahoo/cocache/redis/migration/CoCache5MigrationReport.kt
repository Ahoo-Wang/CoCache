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

package me.ahoo.cocache.redis.migration

/**
 * 迁移选项。
 *
 * @param backupPrefix 原地迁移时 5.x 原值的备份 key 前缀：备份 key = `backupPrefix + 5.x key`，保留原值的类型、内容与剩余寿命。
 *   不能让备份 key 落在任何来源前缀之下（否则会被当作 5.x 数据再次迁移），[CoCache5Migrator] 会校验
 * @param missingGuardSentinel 5.x 的负缓存哨兵（5.x `cocache.redis.missing-guard-sentinel`，默认 `_nil_`）
 * @param scanCount 每次 `SCAN` 的 `COUNT` 提示
 */
data class CoCache5MigrationOptions @JvmOverloads constructor(
    val backupPrefix: String = DEFAULT_BACKUP_PREFIX,
    val missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
    val scanCount: Long = 1000,
) {
    init {
        require(backupPrefix.isNotEmpty()) { "backupPrefix must not be empty." }
        require(scanCount > 0) { "scanCount[$scanCount] must be positive." }
    }

    companion object {
        const val DEFAULT_BACKUP_PREFIX = "cocache5-backup:"
        const val DEFAULT_MISSING_GUARD_SENTINEL = "_nil_"
    }
}

/**
 * 一个缓存的迁移（或回滚）计数。
 *
 * @param read 读取的 5.x key 数（回滚时为读取的备份或 6.x key 数）
 * @param written 写入 6.x 的 key 数（含负缓存；回滚时为恢复或删除的 key 数；[CoCache5Migrator.dryRun] 中为将要写入的数）
 * @param skipped 跳过的 key 数：已是 6.x 格式（重复执行）、读取期间已不存在
 * @param failed 失败的 key 数（迁移遇到第一个失败即中止，因此至多为 1）
 * @param negatives [written] 中 5.x 负缓存的个数（迁移为 6.x 负缓存，保留剩余寿命）
 * @param backedUp 原地迁移时写入的备份数
 */
data class CoCache5CacheReport(
    val name: String,
    val read: Long,
    val written: Long,
    val skipped: Long,
    val failed: Long,
    val negatives: Long,
    val backedUp: Long,
)

/**
 * 迁移（或回滚）报告，按缓存给出计数，顺序与传入的迁移相同。
 */
data class CoCache5MigrationReport(val caches: List<CoCache5CacheReport>) {
    fun cache(name: String): CoCache5CacheReport? = caches.firstOrNull { it.name == name }

    override fun toString(): String = caches.joinToString(prefix = "CoCache5MigrationReport(", postfix = ")")
}

/**
 * 迁移遇到失败（解码失败、Redis 类型与配置的 5.x 格式不符、目标 key 冲突、迁移期间内容被改动、Redis 错误）时立即中止。
 * 已处理的 key 保持已迁移的状态，修正原因后重新执行即可（已迁移的 key 被跳过）。
 *
 * @param report 中止时的报告（失败的缓存 `failed = 1`）
 * @param cacheName 失败的缓存
 * @param key 失败的 5.x key
 */
class CoCache5MigrationException(
    val report: CoCache5MigrationReport,
    val cacheName: String,
    val key: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Migration[$cacheName] - key[$key] - $message", cause)
