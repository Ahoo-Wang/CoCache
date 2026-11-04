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

/**
 * 缓存创建并注册之后的回调（[CacheRuntime.addCreationListener]），无论缓存是否经 [CoCacheFactory] 创建：例如注册指标，
 * 或核对未分层的缓存是否有不生效的配置（RFC 0005 §4.8，S20）。在创建缓存的线程上调用；抛出的异常只记录告警，不影响缓存的创建。
 */
fun interface CacheCreationListener {
    fun created(admin: CacheAdmin)
}
