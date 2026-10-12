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

package me.ahoo.cache.util

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class HostClientIdGeneratorTest {

    @Test
    fun generate() {
        val id1 = HostClientIdGenerator.DEFAULT.generate()
        val id2 = HostClientIdGenerator.DEFAULT.generate()
        id1.assert().isNotEqualTo(id2)
    }

    /**
     * 复现：host 与 pid 都相同的两个实例（如 host 网络模式下同机的两个容器，pid 均为 1）
     * 曾生成相同的 clientId，导致双方把对方的失效事件当作自发事件忽略。
     * 同一 JVM 中的两个生成器实例恰好模拟了 host、pid 与计数器都相同的情形。
     */
    @Test
    fun generateUniqueAcrossInstancesWithSameHostAndProcess() {
        val instance1 = HostClientIdGenerator { "10.0.0.1" }
        val instance2 = HostClientIdGenerator { "10.0.0.1" }
        instance1.generate().assert().isNotEqualTo(instance2.generate())
    }

    @Test
    fun generateWithoutEventDelimiter() {
        HostClientIdGenerator { "10.0.0.1" }.generate().assert().doesNotContain("@@")
    }
}
