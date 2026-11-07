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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.RemoteUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * [HookedBackend.failReads]：只让读取类操作（单 key、批量、条件读取）失败，写入照常。应用测试据此验证读取的降级，
 * 而不必自己包装后端（Wow 的试用发现）。
 */
class HookedBackendFailReadsTest {
    @Test
    fun onlyReadsFail() {
        HookedBackend(InMemoryBackend()).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val store = runtime.create(storeSpec<String, String>("hooked-reads") { codec = StringCodec })
                store.put("k", "v")
                requireNotNull(runtime.admin("hooked-reads")).clearLocal()
                backend.failReads = true

                // 写入照常
                store.put("other", "w")
                // 单 key、批量与条件读取都以 L1 故障失败：权威类型上没有答案
                listOf(
                    { store["k"] },
                    { store.getAll(listOf("k")) },
                    { store.getIfChanged("k", null) },
                ).forEach { read ->
                    val failure = runCatching { read() }.exceptionOrNull()
                    failure.assert().isInstanceOf(CacheReadException::class.java)
                    failure?.cause.assert().isInstanceOf(RemoteUnavailableException::class.java)
                }
                store.lookup("k").assert().isInstanceOf(Lookup.Unknown::class.java)

                backend.failReads = false
                store["k"].assert().isEqualTo("v")
                store.getIfChanged("other", null).assert().isInstanceOf(ConditionalGet.Present::class.java)
            }
        }
    }
}
