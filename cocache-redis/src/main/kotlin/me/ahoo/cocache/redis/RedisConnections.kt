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

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.BaseRedisAsyncCommands
import io.lettuce.core.api.async.RedisScriptingAsyncCommands
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 命令连接与订阅连接（单机或集群），统一脚本执行。
 *
 * 并发的异步命令在同一条共享连接上多路复用；调用方线程同步等待结果，续接中不做任何阻塞工作。
 */
internal class RedisConnections(client: AbstractRedisClient, private val commandTimeout: Duration) : AutoCloseable {
    val cluster: Boolean = client is RedisClusterClient
    private val connection: StatefulConnection<ByteArray, ByteArray>
    private val scripting: RedisScriptingAsyncCommands<ByteArray, ByteArray>
    private val base: BaseRedisAsyncCommands<ByteArray, ByteArray>
    val pubSub: StatefulRedisPubSubConnection<ByteArray, ByteArray>

    init {
        when (client) {
            is RedisClusterClient -> {
                val commands = client.connect(ByteArrayCodec.INSTANCE)
                connection = commands
                scripting = commands.async()
                base = commands.async()
                pubSub = connectPubSub(commands) { client.connectPubSub(ByteArrayCodec.INSTANCE) }
            }

            is RedisClient -> {
                val commands = client.connect(ByteArrayCodec.INSTANCE)
                connection = commands
                scripting = commands.async()
                base = commands.async()
                pubSub = connectPubSub(commands) { client.connectPubSub(ByteArrayCodec.INSTANCE) }
            }

            else -> throw IllegalArgumentException("Unsupported Redis client: ${client.javaClass.name}.")
        }
    }

    /**
     * 异步执行脚本：EVALSHA，遇到 NOSCRIPT（故障切换、重启、resharding 后脚本缓存丢失）时改用 EVAL（会重新缓存）。
     */
    fun <T> evalAsync(script: RedisScripts.Script, keys: List<ByteArray>, args: List<ByteArray>): CompletableFuture<T> {
        val keyArray = keys.toTypedArray()
        val argArray = args.toTypedArray()
        val first: CompletableFuture<T> = scripting.evalsha<T>(
            script.sha,
            ScriptOutputType.MULTI,
            keyArray,
            *argArray
        ).toCompletableFuture()
        return first.exceptionallyCompose { error ->
            val cause = if (error is CompletionException) error.cause ?: error else error
            if (isNoScript(cause)) {
                scripting.eval<T>(script.source, ScriptOutputType.MULTI, keyArray, *argArray).toCompletableFuture()
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    fun <T> eval(script: RedisScripts.Script, keys: List<ByteArray>, args: List<ByteArray>): T = await(
        evalAsync(script, keys, args)
    )

    fun publish(channel: ByteArray, message: ByteArray) {
        await(base.publish(channel, message).toCompletableFuture())
    }

    /**
     * `SPUBLISH`：集群中按频道的 slot 路由到所属主节点。
     */
    fun spublish(channel: ByteArray, message: ByteArray) {
        await(base.spublish(channel, message).toCompletableFuture())
    }

    /**
     * 同步等待：超时、执行异常都转换为运行时异常抛出。
     */
    fun <T> await(future: CompletableFuture<T>): T {
        val failure: RuntimeException = try {
            return future.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            e.cause as? RuntimeException ?: RedisException(e.cause)
        } catch (e: TimeoutException) {
            future.cancel(false)
            RedisException("Command timed out after $commandTimeout.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            RedisException("Interrupted while waiting for Redis.", e)
        }
        throw failure
    }

    /**
     * 各主节点的 `maxmemory-policy`（`CONFIG GET`），键为 `host:port`。`CONFIG` 被禁用、改名或无权限时抛出。
     */
    fun maxmemoryPolicies(): Map<String, String> {
        val connection = connection
        if (connection is StatefulRedisClusterConnection<ByteArray, ByteArray>) {
            return connection.partitions
                .filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
                .associate { node ->
                    val config = await(
                        connection.getConnection(node.nodeId).async().configGet(MAXMEMORY_POLICY).toCompletableFuture()
                    )
                    "${node.uri.host}:${node.uri.port}" to policyOf(config)
                }
        }
        val standalone = connection as StatefulRedisConnection<ByteArray, ByteArray>
        val config = await(standalone.async().configGet(MAXMEMORY_POLICY).toCompletableFuture())
        return mapOf(STANDALONE to policyOf(config))
    }

    private fun policyOf(config: Map<String, String>): String =
        requireNotNull(config[MAXMEMORY_POLICY]) { "CONFIG GET $MAXMEMORY_POLICY returned no value." }

    /**
     * 按 slot 分组（集群）；单机为一组。
     */
    fun groupBySlot(keys: List<ByteArray>): Collection<List<ByteArray>> {
        if (!cluster) {
            return listOf(keys)
        }
        return keys.groupBy { SlotHash.getSlot(it) }.values
    }

    override fun close() {
        pubSub.close()
        connection.close()
    }

    private companion object {
        const val MAXMEMORY_POLICY = "maxmemory-policy"
        const val STANDALONE = "redis"

        /**
         * 建立订阅连接；失败时关闭已建立的命令连接（避免泄漏）再抛出。
         */
        @Suppress("TooGenericExceptionCaught")
        inline fun <C : StatefulRedisPubSubConnection<ByteArray, ByteArray>> connectPubSub(
            commands: StatefulConnection<ByteArray, ByteArray>,
            connect: () -> C
        ): C {
            try {
                return connect()
            } catch (e: RuntimeException) {
                commands.close()
                throw e
            }
        }

        fun isNoScript(error: Throwable): Boolean {
            return error is RedisNoScriptException || error.message?.startsWith("NOSCRIPT") == true
        }
    }
}
