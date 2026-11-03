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

import kotlin.coroutines.cancellation.CancellationException

/**
 * 一次加载以取消结束，而运行它的协程并没有被取消：数据源自己抛出了 `CancellationException`，或返回的 future 以它完成
 * （如数据源内部的 `withTimeout` 超时、被 HTTP 客户端取消的 future）；或挂起数据源的加载协程被取消（`cocache-coroutines`：
 * spec 的 `context` 中的 Job 已被取消，包括加载开始之前就已取消）。
 *
 * 加载可能被多个调用方共享。原样传出的话，引擎会把它当作自己的协程被取消，合并到这次加载的调用方会各自回源，
 * 挂起的调用方会把它当作自己被取消（`launch` 会悄悄吞掉）。因此转为普通异常，与其它回源失败一样交给合并到这次加载的全部调用方
 * （同步、异步与挂起的调用方都收到它）。原始异常是 [cause]。与 [SourceLoadTimeoutException]、[SourceLoadRejectedException]
 * 一样是 [RuntimeException]。
 *
 * 8.0 起在 `cocache-core` 中，覆盖全部数据源（之前是 `me.ahoo.cocache.coroutines.SourceLoadCancelledException`，只覆盖挂起数据源）。
 *
 * @param cacheName 缓存名；数据源不知道自己属于哪个缓存时为 `null`（`asAsyncCacheLoader()` 创建的数据源）
 */
class SourceLoadCancelledException(val cacheName: String?, cause: CancellationException) :
    RuntimeException("Cache[${cacheName ?: "?"}] - the source was cancelled: ${cause.message}", cause)
