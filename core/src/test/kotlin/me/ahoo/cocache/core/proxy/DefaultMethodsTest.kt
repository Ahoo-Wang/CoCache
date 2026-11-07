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

package me.ahoo.cocache.core.proxy

import me.ahoo.cocache.core.proxy.hidden.Declarations
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.invoke.MethodHandles

/**
 * [DefaultMethods]：取不到声明接口的私有查找时（具名模块没有把包开放给 CoCache），创建时就失败（#827）。
 * 这里用没有私有权限的 `publicLookup()` 模拟；可访问的声明接口不需要私有查找。
 */
class DefaultMethodsTest {
    @Test
    fun anInaccessibleDeclaringInterfaceWithoutPrivateAccessFailsAtCreation() {
        val type = Declarations.PublicTokens::class.java
        runCatching { DefaultMethods.of(type, MethodHandles.publicLookup()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(type.name)
            .hasMessageContaining("me.ahoo.cocache.core.proxy.hidden.HiddenBase")
            .hasMessageContaining("open its package")
            .hasCauseInstanceOf(IllegalAccessException::class.java)
    }

    @Test
    fun anAccessibleDeclaringInterfaceNeedsNoPrivateAccess() {
        DefaultMethods.of(Declarations.PublicControl::class.java, MethodHandles.publicLookup()).assert().isNotNull()
    }
}
