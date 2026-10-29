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

import me.ahoo.cocache.api.Versioned
import java.time.Duration
import java.util.Objects

/**
 * 未命中时申请的 lease：[ttl] 到期后由 L1 自动回收（节点崩溃的兜底）。
 */
data class LeaseRequest(val token: Token, val ttl: Duration)

/**
 * 提前刷新：剩余寿命不超过 [window] 且没有未过期的令牌时，写入 [token]；签发超过 [tokenTtl] 的令牌可被抢占。
 */
data class RefreshRequest(val window: Duration, val token: Token, val tokenTtl: Duration)

/**
 * 令牌保护的填充：仅当 L1 中仍是 [token] 时写入 [envelope]，并按 [ttl] 重设到期时间（`null` 为永不过期）。
 */
data class Fill(val key: String, val token: Token, val envelope: Envelope, val ttl: Duration?) {
    /**
     * 与内容一起保存的版本（6.8，按版本有序的写入）；[Versioned.NO_VERSION] 表示没有版本。支持版本的后端
     * （[CoherenceBackend.versionedWrites]）保存它：标量值带版本，[Envelope.Absent] 带版本即墓碑（同 [RemoteStore.putIfNewer] 写入的墓碑）。
     *
     * 四个参数的构造函数取 [Envelope.Scalar.version]（负缓存没有版本）；墓碑经带 `version` 的构造函数给出。
     * 它不是数据类的组成部分（6.7 的构造函数、`copy`、`componentN` 不变）：`copy` 按信封重新取值，不保留墓碑的版本。
     */
    var version: Long = (envelope as? Envelope.Scalar)?.version ?: Versioned.NO_VERSION
        private set

    constructor(key: String, token: Token, envelope: Envelope, ttl: Duration?, version: Long) : this(
        key,
        token,
        envelope,
        ttl
    ) {
        this.version = version
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return other is Fill && key == other.key && token == other.token && envelope == other.envelope &&
            ttl == other.ttl && version == other.version
    }

    override fun hashCode(): Int = Objects.hash(key, token, envelope, ttl, version)

    override fun toString(): String = "Fill(key=$key, token=$token, envelope=$envelope, ttl=$ttl, version=$version)"
}

/**
 * 批量覆盖写入中的一项：语义同 [RemoteStore.put]，按 [ttl] 重设到期时间（`null` 为永不过期）。
 */
data class Put(val key: String, val envelope: Envelope, val ttl: Duration?)
