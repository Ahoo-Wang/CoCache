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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.VersionedL1Store

/**
 * 去掉部分可选能力的后端（测试用，RFC 0005 §4.3）：存储的对应访问器返回 `null`，其余原样转发。
 */
internal class StrippedBackend(
    private val backend: CoherenceBackend,
    private val conditional: Boolean = true,
    private val versioned: Boolean = true,
    private val sets: Boolean = true,
) : CoherenceBackend by backend {
    override fun store(keyspace: Keyspace): L1Store = Stripped(backend.store(keyspace))

    private inner class Stripped(store: L1Store) : ForwardingL1Store(store) {
        override fun conditional(): ConditionalL1Store? = if (conditional) delegate.conditional() else null

        override fun versioned(): VersionedL1Store? = if (versioned) delegate.versioned() else null

        override fun sets(): SetL1Store? = if (sets) delegate.sets() else null
    }
}
