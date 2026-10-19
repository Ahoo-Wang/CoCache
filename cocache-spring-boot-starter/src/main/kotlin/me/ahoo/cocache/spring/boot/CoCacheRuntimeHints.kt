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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.join.JoinCache
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import java.util.concurrent.Executors

/**
 * 与具体声明无关的固定运行时提示：
 * - 代理与校验在运行时反射读取 [Cache] / [SetCache] / [JoinCache] 的公共方法；异步视图 [AsyncCache] / [AsyncSetCache] 的公共方法同样登记
 *   （视图不是代理，由引擎直接实现；登记让经反射调用它们的框架代码可用）；
 * - 运行时的默认阻塞执行器（以及已弃用的 Spring Cache 默认 retrieve 执行器）在 JDK 21+ 上经反射取得
 *   `Executors.newVirtualThreadPerTaskExecutor()`（原生镜像要求 GraalVM 25+）；
 * - L2（Caffeine）按构建器特性拼出类名、经 `MethodHandles.Lookup` 加载生成的实现类与节点类。CoCache 只用
 *   `maximumSize` 与可选的 `expireAfterAccess`，对应的类是固定的四个（见 [CAFFEINE_CLASSES]），不依赖外部元数据仓库。
 *
 * 每个声明各自的提示（代理接口、SpEL 求值对象、Jackson 绑定）由 [CoCacheAotProcessor] 在构建期生成。
 */
internal class CoCacheRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(
            Cache::class.java,
            SetCache::class.java,
            JoinCache::class.java,
            AsyncCache::class.java,
            AsyncSetCache::class.java
        ).forEach {
            hints.reflection().registerType(it, MemberCategory.INVOKE_PUBLIC_METHODS)
        }
        hints.reflection().registerType(Executors::class.java) {
            it.withMethod(VIRTUAL_THREAD_EXECUTOR, emptyList(), ExecutableMode.INVOKE)
        }
        CAFFEINE_CLASSES.forEach {
            hints.reflection().registerTypeIfPresent(
                classLoader,
                it,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS
            )
        }
    }

    companion object {
        const val VIRTUAL_THREAD_EXECUTOR = "newVirtualThreadPerTaskExecutor"

        /**
         * L2 的 Caffeine 生成类：缓存类 `SSMS`（强引用 key / value、按条数淘汰）与 `SSMSA`（再加空闲淘汰），以及对应的节点类。
         */
        val CAFFEINE_CLASSES = listOf("SSMS", "SSMSA", "PSMS", "PSAMS").map { "com.github.benmanes.caffeine.cache.$it" }
    }
}
