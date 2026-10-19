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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisBackendOptions
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails

/**
 * 由 Spring Boot 的 Redis 连接信息构造后端。后端自建 Lettuce 客户端（断线时拒绝命令、只访问主节点），
 * 不复用应用的连接工厂：客户端选项是全局的，不能为 CoCache 改动应用的连接行为。
 */
object RedisBackends {
    fun create(details: DataRedisConnectionDetails, options: RedisBackendOptions): RedisBackend {
        val ssl = details.sslBundle != null
        details.cluster?.let { cluster ->
            val uris = cluster.nodes.map { node ->
                auth(
                    RedisURI.Builder.redis(node.host, node.port),
                    details.username,
                    details.password
                ).withSsl(ssl).build()
            }
            return RedisBackend.cluster(uris, options)
        }
        details.sentinel?.let { sentinel ->
            val first = sentinel.nodes.first()
            val builder = RedisURI.Builder.sentinel(
                first.host,
                first.port,
                sentinel.master
            ).withDatabase(sentinel.database).withSsl(ssl)
            sentinel.nodes.drop(1).forEach { builder.withSentinel(it.host, it.port) }
            return RedisBackend.standalone(auth(builder, details.username, details.password).build(), options)
        }
        val standalone = requireNotNull(
            details.standalone
        ) { "Redis connection details define neither cluster, sentinel nor standalone." }
        val builder = RedisURI.Builder.redis(
            standalone.host,
            standalone.port
        ).withDatabase(standalone.database).withSsl(ssl)
        return RedisBackend.standalone(auth(builder, details.username, details.password).build(), options)
    }

    private fun auth(builder: RedisURI.Builder, username: String?, password: String?): RedisURI.Builder {
        if (password.isNullOrEmpty()) {
            return builder
        }
        return if (username.isNullOrEmpty()) {
            builder.withPassword(
                password.toCharArray()
            )
        } else {
            builder.withAuthentication(username, password)
        }
    }
}
