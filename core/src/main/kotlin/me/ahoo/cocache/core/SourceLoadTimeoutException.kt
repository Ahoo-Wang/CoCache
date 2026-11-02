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

import java.time.Duration

/**
 * 一次加载超过了 [timeout]（`LoadPolicy.timeout`）：异步数据源（`AsyncCacheLoader`）的一次加载（lease 已释放，迟到的结果被丢弃），
 * 或跟随者等待共享的加载超过了这次加载的截止时刻。
 */
class SourceLoadTimeoutException(val cacheName: String, val timeout: Duration) :
    RuntimeException("Cache[$cacheName] - load timed out after $timeout.")
