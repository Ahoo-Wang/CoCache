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

package me.ahoo.cocache.spring

import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.util.ClassUtils

/**
 * Spring 集成与 starter 共用的常量：spring-tx 是可选依赖，是否可用按类名判断；没有缓存通知时用 Spring 默认的错误处理器。
 *
 * 内部实现（`cocache-spring` 与 starter 接线使用），不在兼容性承诺之内。
 */
object SpringSupport {
    /**
     * spring-tx 的事务同步管理器（`compileOnly`，不引用它的类）。
     */
    const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"

    /**
     * Spring 默认的 [CacheErrorHandler]（无状态，共用一个实例）。
     */
    @JvmField
    val SIMPLE_ERROR_HANDLER: CacheErrorHandler = SimpleCacheErrorHandler()

    /**
     * spring-tx 在 [classLoader] 中可用。
     */
    @JvmStatic
    fun transactionsPresent(
        classLoader: ClassLoader?
    ): Boolean = ClassUtils.isPresent(TRANSACTION_SYNCHRONIZATION, classLoader)
}
