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

package me.ahoo.cocache.api.spi

/**
 * 失效通道：至多一次投递。
 *
 * 实现约定：每次订阅（重新）建立并得到服务端确认之后，必须回调该连接上所有订阅者的 [InvalidationListener.onReset]。
 */
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription

    /**
     * 仅当 [CoherenceBackend.atomicBroadcast] 为 `false` 时由引擎调用。
     */
    fun publish(namespace: String, frame: ByteArray)
}

/**
 * 失效订阅者。
 */
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)

    /**
     * 订阅（重新）建立：此前的事件可能已丢失，必须放弃所有本地副本。
     */
    fun onReset()
}

/**
 * 订阅句柄，关闭即取消订阅。
 */
fun interface Subscription : AutoCloseable {
    override fun close()
}
