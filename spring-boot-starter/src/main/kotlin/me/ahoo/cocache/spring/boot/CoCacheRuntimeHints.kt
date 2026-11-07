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
import me.ahoo.cocache.api.AsyncReadableCache
import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.AsyncVersionedCache
import me.ahoo.cocache.api.AsyncVersionedStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.SuspendingReadableCache
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.SuspendingVersionedStore
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinView
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 与具体声明无关的固定运行时提示：
 * - 代理与校验在运行时反射读取五种用户 API 类型与组合视图的公共方法（[API_TYPES]）；它们的异步视图与挂起视图同样登记
 *   （视图不是代理，由引擎直接实现；登记让经反射调用它们的框架代码可用）；
 * - L2（Caffeine）按构建器特性拼出类名、经 `MethodHandles.Lookup` 加载生成的实现类与节点类。CoCache 只用
 *   `maximumSize` 与可选的 `expireAfterAccess`，对应的类是固定的四个（见 [CAFFEINE_CLASSES]），不依赖外部元数据仓库；
 * - Redis Cluster 的经典 Pub/Sub 心跳失败时，`cocache-redis` 经反射调用 Lettuce 包私有的集群写入器的公开方法
 *   `disconnectDefaultEndpoint()` 强制重连（[CLUSTER_WRITER]）。没有这条提示，原生镜像里取不到这个方法，退化为只告警、依赖 TCP keepalive。
 * - Kotlin 的空集合单例（[KOTLIN_EMPTY_COLLECTIONS]：`emptyList()`、`emptySet()`、`emptyMap()` 的运行时类型）：Jackson 按运行时类型序列化，
 *   jackson-module-kotlin 对 Kotlin 类做 kotlin-reflect 内省，要读它们的类元数据与 `INSTANCE`。原生镜像里没有登记时内省失败
 *   （`KotlinReflectionInternalError: Unresolved class: class kotlin.collections.EmptyList`）：actuator 端点的报告中
 *   空的列表（如 `ignoredConfiguration`）让 `GET /actuator/cocache` 返回 500，用户的缓存值经 `JacksonCodec` 编码空集合时同样失败。
 *
 * 每个声明各自的提示（代理接口、SpEL 求值对象、Jackson 绑定）由 [CoCacheAotProcessor] 在构建期生成。
 */
internal class CoCacheRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        API_TYPES.forEach {
            hints.reflection().registerType(it, MemberCategory.INVOKE_PUBLIC_METHODS)
        }
        hints.reflection().registerTypeIfPresent(classLoader, CLUSTER_WRITER) {
            it.withMethod(DISCONNECT_DEFAULT_ENDPOINT, emptyList(), ExecutableMode.INVOKE)
        }
        CAFFEINE_CLASSES.forEach {
            hints.reflection().registerTypeIfPresent(
                classLoader,
                it,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS
            )
        }
        KOTLIN_EMPTY_COLLECTIONS.forEach {
            hints.reflection().registerTypeIfPresent(
                classLoader,
                it,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                // `INSTANCE` 是公开的静态字段：公开字段要 ACCESS_PUBLIC_FIELDS，DECLARED_* 只覆盖非公开的
                MemberCategory.ACCESS_PUBLIC_FIELDS,
                MemberCategory.ACCESS_DECLARED_FIELDS
            )
        }
    }

    companion object {
        /**
         * 用户 API 的类型（RFC 0005 §3.1）：同步、异步与挂起视图，以及组合视图。
         */
        val API_TYPES: List<Class<*>> = listOf(
            ReadableCache::class.java,
            Cache::class.java,
            VersionedCache::class.java,
            Store::class.java,
            VersionedStore::class.java,
            SetStore::class.java,
            AsyncReadableCache::class.java,
            AsyncCache::class.java,
            AsyncVersionedCache::class.java,
            AsyncStore::class.java,
            AsyncVersionedStore::class.java,
            AsyncSetStore::class.java,
            SuspendingReadableCache::class.java,
            SuspendingCache::class.java,
            SuspendingVersionedCache::class.java,
            SuspendingStore::class.java,
            SuspendingVersionedStore::class.java,
            SuspendingSetStore::class.java,
            JoinView::class.java,
            JoinCache::class.java,
        )

        /**
         * Lettuce 的集群写入器（包私有类）与 `cocache-redis` 经反射调用的方法（`RedisInvalidationChannel.disconnect`）。
         */
        const val CLUSTER_WRITER = "io.lettuce.core.cluster.ClusterDistributionChannelWriter"

        /**
         * Kotlin 的空集合单例（`kotlin-stdlib` 的内部类）：`emptyList()`、`emptySet()`、`emptyMap()` 的运行时类型。
         */
        val KOTLIN_EMPTY_COLLECTIONS: List<String> = listOf(
            "kotlin.collections.EmptyList",
            "kotlin.collections.EmptySet",
            "kotlin.collections.EmptyMap",
        )
        const val DISCONNECT_DEFAULT_ENDPOINT = "disconnectDefaultEndpoint"

        /**
         * L2 的 Caffeine 生成类：缓存类 `SSMS`（强引用 key / value、按条数淘汰）与 `SSMSA`（再加空闲淘汰），以及对应的节点类。
         */
        val CAFFEINE_CLASSES = listOf("SSMS", "SSMSA", "PSMS", "PSAMS").map { "com.github.benmanes.caffeine.cache.$it" }
    }
}
