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

package me.ahoo.cocache.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 经典 Pub/Sub 失效通道（`SUBSCRIBE` / `PUBLISH`）。Cluster 中订阅连接只连一个节点，`PUBLISH` 经集群总线发往全部节点。
 */
internal class PubSubInvalidationChannel(
    connections: RedisConnections,
    heartbeat: Duration,
    ackTimeout: Duration,
) : RedisInvalidationChannel(connections, heartbeat, ackTimeout) {
    init {
        connections.pubSub.addListener(Adapter())
        start()
    }

    override fun subscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().subscribe(channel).toCompletableFuture()
    }

    override fun unsubscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().unsubscribe(channel).toCompletableFuture()
    }

    override fun publish(namespace: String, frame: ByteArray) {
        connections.publish(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)
    }

    override fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?> =
        connections.publishAsync(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)

    override fun ping() {
        connections.await(connections.pubSub.async().ping().toCompletableFuture())
    }

    private inner class Adapter : RedisPubSubAdapter<ByteArray, ByteArray>() {
        override fun message(channel: ByteArray, message: ByteArray) = dispatch(channel, message)

        override fun subscribed(channel: ByteArray, count: Long) = acknowledged(channel)
    }
}
