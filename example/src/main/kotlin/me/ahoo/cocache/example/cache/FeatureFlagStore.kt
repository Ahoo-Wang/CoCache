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

package me.ahoo.cocache.example.cache

import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.annotation.CoCache

/**
 * 权威副本（RFC 0005 §4.1）：功能开关只保存在 Redis 中，L1 丢了就没有别处可以重建，因此是 `Store`，不是 `Cache`。
 * 没有数据源；读取不申请 lease，L1 中没有就是不存在；删除同步完成或抛出，从不重试；默认永不过期。
 * 需要不驱逐 key 的 Redis（`maxmemory-policy` 为 `noeviction`），starter 启动时检查（`cocache.redis.store-eviction-check`）。
 */
@CoCache(keyPrefix = "flag:")
interface FeatureFlagStore : Store<String, String>
