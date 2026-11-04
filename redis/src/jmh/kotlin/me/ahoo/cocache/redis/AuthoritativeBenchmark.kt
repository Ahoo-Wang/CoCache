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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 权威标量的基准（RFC 0005 §7）：载荷 1 KB / 1 MB / 8 MB。
 * - [conditionalUnchanged]：摘要未变的条件读取，脚本只读头部（`GETRANGE` + `STRLEN`），Redis 端成本与载荷大小无关；
 * - [authoritativeL1Read]：没有数据源的缓存的 L1 读取，读取方重算 SHA-256 校验摘要段（K3）；
 * - [derivedL1Read]：有数据源的缓存读同样大小的值，不校验：与上一项之差即校验的成本。
 *
 * 两个 L1 读取每次调用之前清空本节点的 L2（[Level.Invocation]，不计入测量），所以每次都读 L1。
 *
 * 需要 localhost:6379 的 Redis：`./gradlew :cocache-redis:jmh --rerun -PjmhIncludes=AuthoritativeBenchmark`。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class AuthoritativeBenchmark {
    @Param("1024", "1048576", "8388608")
    var payloadSize: Int = 0

    private val run = UUID.randomUUID().toString().substring(0, 8)
    private lateinit var backend: RedisBackend
    private lateinit var runtime: CacheRuntime
    private lateinit var authoritative: Cache<String, String>
    private lateinit var derived: Cache<String, String>
    private lateinit var authoritativeAdmin: CacheAdmin
    private lateinit var derivedAdmin: CacheAdmin
    private lateinit var digest: Digest

    @Setup
    fun setup() {
        backend = RedisBackend.standalone(RedisURI.create("localhost", 6379))
        runtime = CacheRuntime.builder().backend(backend).build()
        val document = "x".repeat(payloadSize)
        val policy = CachePolicy(ttl = null)
        authoritative = runtime.create(
            cacheSpec<String, String>("bench-record-$run") {
                codec = StringCodec
                this.policy = policy
            }
        )
        derived = runtime.create(
            cacheSpec<String, String>("bench-derived-$run") {
                codec = StringCodec
                loader = CacheLoader { document }
                this.policy = policy
            }
        )
        authoritative.put("doc", document)
        derived.put("doc", document)
        digest = Digest.ofPayload(StringCodec.encode(document))
        authoritativeAdmin = requireNotNull(runtime.admin("bench-record-$run"))
        derivedAdmin = requireNotNull(runtime.admin("bench-derived-$run"))
        check(authoritative.getIfChanged("doc", digest) == ConditionalGet.Unchanged)
    }

    @TearDown
    fun tearDown() {
        authoritative.invalidate("doc")
        derived.invalidate("doc")
        runtime.close()
        backend.close()
    }

    /**
     * 每次调用之前清空 L2：L1 读取的基准每次都读 L1。
     */
    @Setup(Level.Invocation)
    fun clearLocal() {
        authoritativeAdmin.clearLocal()
        derivedAdmin.clearLocal()
    }

    /** 摘要未变的条件读取：只传输结果码与格式版本。 */
    @Benchmark
    fun conditionalUnchanged(): ConditionalGet<String> = authoritative.getIfChanged("doc", digest)

    /** 没有数据源的缓存的 L1 读取：重算 SHA-256 校验摘要段，再解码、填 L2。 */
    @Benchmark
    fun authoritativeL1Read(): String? = authoritative["doc"]

    /** 有数据源的缓存的 L1 读取：不校验，解码、填 L2。 */
    @Benchmark
    fun derivedL1Read(): String? = derived["doc"]
}
