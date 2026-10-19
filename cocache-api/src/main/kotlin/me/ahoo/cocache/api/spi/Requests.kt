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
data class Fill(val key: String, val token: Token, val envelope: Envelope, val ttl: Duration?)
