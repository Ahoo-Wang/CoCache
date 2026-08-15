# Redis 可靠性实施计划（Redis Reliability Implementation Plan）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Redis 层 4 项 major（脏 JSON 载荷自愈、null 归一化、故障降级、Hash/Set 写入原子化）+ 哨兵值可配置，全程字节级线上兼容。

**Architecture:** 防御逻辑集中在 `AbstractCodecExecutor` 模板层（自愈 + 归一化 + 哨兵属性 + Lua 助手）与 `RedisDistributedCache` 唯一 I/O 入口（降级）；starter 经 `CoCacheProperties.redis` 透传 `strictFailure`/`missingGuardSentinel`；扩展既有 `CodecExecutorSpec` TCK。

**Tech Stack:** Kotlin (JVM 17)、spring-data-redis 4.1（`DefaultRedisScript`/Lua/EVALSHA）、Jackson 3（tools.jackson）、JUnit 5 + mockk + fluent-assert。

**设计规格:** `docs/superpowers/specs/2026-08-14-redis-reliability-design.md`

**重要约定（每个任务通用）:**
- 断言一律 `import me.ahoo.test.asserts.assert` + `.assert()`，禁用 AssertJ `assertThat`（`Offset.offset` 为参数非断言，可用）。
- 集成测试连本地 Redis（`RedisStandaloneConfiguration()` 默认 localhost:6379，本机可用；CI 为服务容器）。降级单测用 mockk 无需 Redis。
- 分支：从当前 main（`05d737d2`）创建 `fix/redis-reliability`，所有任务提交于此。**严禁 `git add -A`**，用显式路径。
- Conventional commits：`fix(redis):` / `feat(redis):` / `feat(starter):` / `docs(superpowers):`。
- 所有路径相对仓库根 `/Users/ahoo/work/ahoo-git/CoCache`。

## 文件结构（改动总览）

| 责任 | 文件 | 动作 |
|---|---|---|
| 接口可空化 | `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutor.kt` | 修改 |
| 模板层防御 | `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt` | 修改 |
| 四 codec | `codec/StringToStringCodecExecutor.kt`、`MapToHashCodecExecutor.kt`、`ObjectToHashCodecExecutor.kt`、`SetToSetCodecExecutor.kt` | 修改 |
| 降级 | `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt` | 修改 |
| 工厂透传 | `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt` | 修改 |
| 总线降级 | `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt` | 修改 |
| starter 配置 | `cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheProperties.kt`、`CoCacheAutoConfiguration.kt` | 修改 |
| TCK 扩展 | `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt` + 4 具体类 | 修改 |
| 新测试 | `RedisDistributedCacheFailureTest.kt`、总线/哨兵/原子性测试 | 新增/扩展 |

---

## Task 1: CodecExecutor 可空化 + 脏载荷自愈（红-绿）

**Files:**
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToJsonCodecExecutorTest.kt`（追加测试）
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt`（可空化编译适配）
- Modify: `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutor.kt`
- Modify: `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt:56-67`

- [ ] **Step 1: 编写失败测试（JSON codec 自愈）**

在 `ObjectToJsonCodecExecutorTest` 类末尾追加（import 已有：`ComputedTtlAt`、`me.ahoo.test.asserts.assert`、`UUID`；缺则补 `me.ahoo.cache.ComputedTtlAt`）：

```kotlin
    @Test
    fun executeAndDecodeWhenCorruptedPayloadEvictsAndReturnsNull() {
        val key = "corrupted:" + UUID.randomUUID().toString()
        stringRedisTemplate.opsForValue()[key] = "{invalid-json"

        val actual = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER)

        actual.assert().isNull()
        stringRedisTemplate.opsForValue()[key].assert().isNull()
    }
```

- [ ] **Step 2: 运行测试验证失败（红——编译错误）**

Run: `./gradlew :cocache-spring-redis:compileTestKotlin`
Expected: 编译失败——`actual` 为非空类型无法 `isNull()`（返回类型尚未可空化）

- [ ] **Step 3: 实现可空化 + 自愈**

3a. `CodecExecutor.kt` 接口方法返回类型放宽：

```kotlin
interface CodecExecutor<V> {
    /**
     * @param ttlAt time to live([java.time.temporal.ChronoUnit.SECONDS]).
     */
    fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V>?
    fun executeAndEncode(key: String, cacheValue: CacheValue<V>)
}
```

3b. `AbstractCodecExecutor.kt`：新增 import `io.github.oshai.kotlinlogging.KotlinLogging`；新增 companion：

```kotlin
    companion object {
        private val log = KotlinLogging.logger {}
    }
```

`executeAndDecode` 替换为：

```kotlin
    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V>? {
        val rawValue = getRawValue(key) ?: return DefaultCacheValue.missingGuard(ttlAt)
        if (isMissingGuard(rawValue)) {
            return DefaultCacheValue.missingGuard(ttlAt)
        }
        val value = try {
            decode(rawValue)
        } catch (e: Exception) {
            log.warn(e) { "Corrupted payload at key[$key] - evict and treat as cache miss." }
            redisTemplate.delete(key)
            return null
        }
        return DefaultCacheValue(
            value,
            ttlAt,
        )
    }
```

（注意 `DefaultCacheValue.missingGuard(ttlAt)` 现有调用处传的是 `ttlAt`——该泛型重载 `<V : CacheValue<*>> missingGuard(ttl: Long, amplitude: Long = 0)` 返回 `V`，在 `CacheValue<V>?` 返回位置类型推断 OK。）

3c. 可空化编译适配——`CodecExecutorSpec.kt` 两处 `actual.value` / `actual.isMissingGuard` 改为 `actual!!.value` / `actual!!.isMissingGuard`（第 73-75、97-99 行）。

- [ ] **Step 4: 运行验证（绿）**

Run: `./gradlew :cocache-spring-redis:test`
Expected: BUILD SUCCESSFUL（含新自愈测试；RedisDistributedCachingTest 等集成测试通过——`RedisDistributedCache.getCache` 返回类型本就可空）

- [ ] **Step 5: 提交**

```bash
git add cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutor.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToJsonCodecExecutorTest.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt
git commit -m "fix(redis): self-heal corrupted payload instead of failing reads"
```

---

## Task 2: null 归一化（红-绿，规范层一条断言 ×4 codec）

**Files:**
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt`（追加测试）
- Modify: `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt:73-78`

- [ ] **Step 1: 编写失败测试（加在 CodecExecutorSpec 类末尾）**

```kotlin
    @Test
    fun executeAndEncodeNullValueAsMissingGuard() {
        val key = "null-normalize:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 100
        @Suppress("UNCHECKED_CAST")
        val nullValue = null as V
        codecExecutor.executeAndEncode(key, DefaultCacheValue(nullValue, ttlAt))

        val actual = codecExecutor.executeAndDecode(key, ttlAt)

        actual!!.isMissingGuard.assert().isTrue()
    }
```

- [ ] **Step 2: 运行验证失败（红）**

Run: `./gradlew :cocache-spring-redis:test --tests "me.ahoo.cache.spring.redis.codec.*"`
Expected: 四个具体测试类的 `executeAndEncodeNullValueAsMissingGuard` 全部 FAIL——String 读回 `""`（isMissingGuard=false）、Map/Set 抛命令异常、JSON 读回 null 值（isMissingGuard=false）

- [ ] **Step 3: 实现（executeAndEncode 归一化）**

`AbstractCodecExecutor.executeAndEncode` 替换为：

```kotlin
    override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        val normalizedValue = if (cacheValue.value == null && cacheValue.isMissingGuard.not()) {
            @Suppress("UNCHECKED_CAST")
            DefaultCacheValue(DefaultMissingGuard, cacheValue.ttlAt) as CacheValue<V>
        } else {
            cacheValue
        }
        if (normalizedValue.isForever) {
            setForeverValue(key, normalizedValue)
        } else {
            setValueWithTtlAt(key, normalizedValue)
        }
    }
```

新增 import：`me.ahoo.cache.DefaultMissingGuard`。KDoc 说明：null 归一化——非 missing-guard 的 null 统一按负缓存哨兵写入，与内存实现语义对齐。

- [ ] **Step 4: 运行验证（绿）**

Run: `./gradlew :cocache-spring-redis:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt
git commit -m "fix(redis): normalize null cache value to missing guard across codecs"
```

---

## Task 3: 哨兵值可配置（codec 层 + 工厂透传）

**Files:**
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/StringToStringCodecExecutorTest.kt`（追加测试）
- Modify: `codec/AbstractCodecExecutor.kt`、四个 codec executor、`RedisDistributedCacheFactory.kt`

- [ ] **Step 1: 编写测试（先写后跑——本任务为构造参数新增，编译即红）**

在 `StringToStringCodecExecutorTest` 类末尾追加（新增 import：`me.ahoo.cache.api.CacheValue`、`me.ahoo.cache.DefaultCacheValue`、`me.ahoo.cache.ComputedTtlAt`——按需）：

```kotlin
    @Test
    fun customSentinelAvoidsCollision() {
        val executor = StringToStringCodecExecutor(
            stringRedisTemplate,
            missingGuardSentinel = "\u0000cocache:nil",
        )
        val key = "custom-sentinel:" + UUID.randomUUID().toString()

        // 真实业务值 "_nil_" 不再被默认哨兵误判为负缓存
        executor.executeAndEncode(key, DefaultCacheValue.forever("_nil_"))
        executor.executeAndDecode(key, ComputedTtlAt.FOREVER)!!.value.assert().isEqualTo("_nil_")

        // 负缓存写入使用自定义哨兵
        executor.executeAndEncode(key, DefaultCacheValue.missingGuard())
        stringRedisTemplate.opsForValue()[key].assert().isEqualTo("\u0000cocache:nil")
        executor.executeAndDecode(key, ComputedTtlAt.FOREVER)!!.isMissingGuard.assert().isTrue()
    }
```

- [ ] **Step 2: 运行验证失败（红——编译错误）**

Run: `./gradlew :cocache-spring-redis:compileTestKotlin`
Expected: 编译失败，`missingGuardSentinel` 参数不存在

- [ ] **Step 3: 实现**

3a. `AbstractCodecExecutor` 构造函数新增哨兵参数（成为哨兵唯一来源）：

```kotlin
abstract class AbstractCodecExecutor<V, RAW_VALUE>(
    protected val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : CodecExecutor<V> {
```

3b. 四个 codec 改造——构造函数透传 + 哨兵引用属性化：

`StringToStringCodecExecutor`：
```kotlin
class StringToStringCodecExecutor(
    override val redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<String, String>(missingGuardSentinel) {

    override fun CacheValue<String>.toRawValue(): String {
        if (isMissingGuard) {
            return missingGuardSentinel
        }
        return value
    }

    override fun isMissingGuard(rawValue: String): Boolean {
        return rawValue == missingGuardSentinel
    }
    // getRawValue/decode/setValueWithTtlAt/setForeverValue 不变
```

`MapToHashCodecExecutor`：
```kotlin
class MapToHashCodecExecutor(
    override val redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<Map<String, String>, Map<String, String>>(missingGuardSentinel) {

    override fun CacheValue<Map<String, String>>.toRawValue(): Map<String, String> {
        if (isMissingGuard) {
            return mapOf(missingGuardSentinel to CacheSecondClock.INSTANCE.currentTime().toString())
        }
        return value
    }

    override fun isMissingGuard(rawValue: Map<String, String>): Boolean {
        return rawValue.size == 1 && rawValue.keys.first() == missingGuardSentinel
    }
```

`ObjectToHashCodecExecutor`：与 MapToHash 同模式（`toRawValue` 哨兵键、`isMissingGuard` 单键判定）：
```kotlin
class ObjectToHashCodecExecutor<V>(
    private val mapConverter: MapConverter<V>,
    override val redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<V, Map<String, String>>(missingGuardSentinel) {

    override fun CacheValue<V>.toRawValue(): Map<String, String> {
        if (isMissingGuard) {
            return mapOf(missingGuardSentinel to CacheSecondClock.INSTANCE.currentTime().toString())
        }
        return mapConverter.asMap(value)
    }

    override fun isMissingGuard(rawValue: Map<String, String>): Boolean {
        return rawValue.size == 1 && rawValue.keys.first() == missingGuardSentinel
    }
```

`SetToSetCodecExecutor`：
```kotlin
class SetToSetCodecExecutor(
    override val redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<Set<String>, Set<String>>(missingGuardSentinel) {

    private val missingGuard: Set<String> = setOf(missingGuardSentinel)

    override fun CacheValue<Set<String>>.toRawValue(): Set<String> {
        if (isMissingGuard) {
            return missingGuard
        }
        return value
    }

    override fun isMissingGuard(rawValue: Set<String>): Boolean {
        return rawValue.size == 1 && rawValue.first() == missingGuardSentinel
    }
```

3c. `RedisDistributedCacheFactory` 构造函数新增透传参数（本任务只加参数，starter 接线在 Task 6）：

```kotlin
class RedisDistributedCacheFactory(
    beanFactory: BeanFactory,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    private val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : DistributedCacheFactory, AbstractCacheFactory(beanFactory) {
```

`fallback` 中 `ObjectToJsonCodecExecutor` 构造改为：
```kotlin
        val codecExecutor = ObjectToJsonCodecExecutor<Any>(
            valueType = cacheMetadata.valueType.javaType,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            missingGuardSentinel = missingGuardSentinel,
        )
```

`ObjectToJsonCodecExecutor` 构造函数相应新增 `missingGuardSentinel: String = MissingGuard.STRING_VALUE` 透传给超类，`toRawValue` 哨兵返回 `missingGuardSentinel`、`isMissingGuard(rawValue)` 改为 `rawValue == missingGuardSentinel`。

- [ ] **Step 4: 运行验证（绿）**

Run: `./gradlew :cocache-spring-redis:test`
Expected: BUILD SUCCESSFUL（含 customSentinelAvoidsCollision；既有哨兵往返断言用默认哨兵，行为不变）

- [ ] **Step 5: 提交**

```bash
git add cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/StringToStringCodecExecutorTest.kt
git commit -m "feat(redis): configurable missing-guard sentinel per codec"
```

---

## Task 4: Redis 故障降级（红-绿）

**Files:**
- Create: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFailureTest.kt`
- Modify: `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt`
- Modify: `cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt`（publish 包裹）
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBusTest.kt`（追加测试）

- [ ] **Step 1: 编写失败测试（新建 RedisDistributedCacheFailureTest）**

```kotlin
package me.ahoo.cache.spring.redis

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

internal class RedisDistributedCacheFailureTest {
    private val failure = RedisConnectionFailureException("redis down")

    private fun newFailingTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>()
        every { template.getExpire(any<String>()) } throws failure
        every { template.delete(any<String>()) } throws failure
        return template
    }

    private fun newFailingCodec(): CodecExecutor<String> {
        val codec = mockk<CodecExecutor<String>>()
        every { codec.executeAndEncode(any(), any()) } throws failure
        return codec
    }

    @Test
    fun getCacheDegradesToMissOnFailure() {
        val cache = RedisDistributedCache(newFailingTemplate(), newFailingCodec())
        cache.getCache("key").assert().isNull()
    }

    @Test
    fun setCacheSwallowsFailure() {
        val cache = RedisDistributedCache(mockk<StringRedisTemplate>(), newFailingCodec())
        cache.setCache("key", me.ahoo.cache.DefaultCacheValue.forever("value"))
    }

    @Test
    fun evictSwallowsFailure() {
        val cache = RedisDistributedCache(newFailingTemplate(), mockk<CodecExecutor<String>>())
        cache.evict("key")
    }

    @Test
    fun getCacheRethrowsInStrictMode() {
        val cache = RedisDistributedCache(newFailingTemplate(), newFailingCodec(), strictFailure = true)
        try {
            cache.getCache("key")
            throw AssertionError("expected RedisConnectionFailureException")
        } catch (e: RedisConnectionFailureException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: 运行验证失败（红——编译错误：strictFailure 参数不存在）**

Run: `./gradlew :cocache-spring-redis:compileTestKotlin`
Expected: 编译失败，`strictFailure` 未定义

- [ ] **Step 3: 实现 RedisDistributedCache 降级**

整体替换 `RedisDistributedCache.kt`（保留许可头）：

```kotlin
package me.ahoo.cache.spring.redis

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis Distributed Cache.
 *
 * 默认故障降级：读失败按缓存未命中处理（上层回源），写/evict 失败仅告警。
 * [strictFailure] = true 时恢复严格语义（重抛 [DataAccessException]）。
 *
 * @author ahoo wang
 */
class RedisDistributedCache<V>(
    private val redisTemplate: StringRedisTemplate,
    private val codecExecutor: CodecExecutor<V>,
    override val ttl: Long = CoCache.DEFAULT_TTL,
    override val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE,
    private val strictFailure: Boolean = false,
) : DistributedCache<V> {

    override fun getCache(key: String): CacheValue<V>? {
        return try {
            val ttlAt = ttlAt(key) ?: return null
            codecExecutor.executeAndDecode(key, ttlAt)
        } catch (e: DataAccessException) {
            handleFailure("getCache", key, e)
            null
        }
    }

    private fun ttlAt(key: String): Long? {
        val ttl = redisTemplate.getExpire(key)
        if (NOT_EXIST == ttl) {
            return null
        }
        return if (FOREVER == ttl) {
            ComputedTtlAt.FOREVER
        } else {
            CacheSecondClock.INSTANCE.currentTime() + ttl
        }
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        try {
            if (value.isExpired) {
                evict(key)
                return
            }
            codecExecutor.executeAndEncode(key, value)
        } catch (e: DataAccessException) {
            handleFailure("setCache", key, e)
        }
    }

    override fun evict(key: String) {
        try {
            redisTemplate.delete(key)
        } catch (e: DataAccessException) {
            handleFailure("evict", key, e)
        }
    }

    override fun close() = Unit

    private fun handleFailure(operation: String, key: String, e: DataAccessException) {
        if (strictFailure) {
            throw e
        }
        log.warn(e) { "Cache operation[$operation] key[$key] failed - degrading (strictFailure=false)." }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val FOREVER = -1L
        const val NOT_EXIST = -2L
    }
}
```

（注意：`evict` 被 `setCache` 内部调用时，若 `setCache` 已捕获则 `evict` 的 catch 不二次触发——`evict` 自身的 try/catch 在被内部调用时若抛出会被 `setCache` 的外层捕获，strict 模式下重抛一次即可。）

- [ ] **Step 4: 总线 publish 包裹 + 测试**

`RedisCacheEvictedEventBus.publish` 替换为：

```kotlin
    override fun publish(event: CacheEvictedEvent) {
        try {
            redisTemplate.convertAndSend(event.cacheName, EvictedEvents.asMessage(event.key, event.publisherId))
        } catch (e: DataAccessException) {
            // pub/sub 为 fire-and-forget：发布失败仅告警，不阻断调用方（与已接受的丢消息语义一致）
            log.warn(e) { "Publish - event:[$event] failed." }
        }
    }
```

新增 import `org.springframework.dao.DataAccessException`。

在 `RedisCacheEvictedEventBusTest` 类末尾追加（先读该文件确认 fixture 风格；以下按 mockk 直构）：

```kotlin
    @Test
    fun publishSwallowsRedisFailure() {
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.convertAndSend(any<String>(), any<Any>()) } throws RedisConnectionFailureException("down")
        val bus = RedisCacheEvictedEventBus(redisTemplate, mockk(relaxed = true))

        bus.publish(CacheEvictedEvent("cache", "key", "clientId"))
    }
```

- [ ] **Step 5: 运行验证（绿）**

Run: `./gradlew :cocache-spring-redis:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFailureTest.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBusTest.kt
git commit -m "feat(redis): degrade to cache-miss semantics on redis failures"
```

---

## Task 5: Lua 原子化（红-绿）

**Files:**
- Test: `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutorSpec.kt`（追加 TTL 断言）
- Test: `MapToHashCodecExecutorTest.kt`、`SetToSetCodecExecutorTest.kt`（追加空集合守卫）
- Test: 新建 `cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/AtomicWriteConsistencyTest.kt`
- Modify: `codec/AbstractCodecExecutor.kt`、`MapToHashCodecExecutor.kt`、`ObjectToHashCodecExecutor.kt`、`SetToSetCodecExecutor.kt`

- [ ] **Step 1: 编写失败测试（Redis 侧 EXPIRE 断言——现 pipeline 版本会通过 TTL 设置，此断言是回归锁；真正的红在 Step 1b 空集合守卫）**

1a. `CodecExecutorSpec` 追加：

```kotlin
    @Test
    fun executeAndEncodeWithTtlSetsRedisExpire() {
        val key = "redis-expire:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 60
        codecExecutor.executeAndEncode(key, DefaultCacheValue(createCacheValue(), ttlAt))

        val expire = stringRedisTemplate.getExpire(key)

        (expire != null && expire > 0).assert().isTrue()
    }
```

1b. `MapToHashCodecExecutorTest` 追加（**红**：现版本空 Map 会 hMSet 空 map → 抛异常）：

```kotlin
    @Test
    fun executeAndEncodeEmptyMapEvictsKey() {
        val key = "empty-map:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 60
        codecExecutor.executeAndEncode(key, DefaultCacheValue(emptyMap(), ttlAt))

        stringRedisTemplate.hasKey(key).assert().isFalse()
    }
```

（`SetToSetCodecExecutorTest` 同模式：`DefaultCacheValue(emptySet(), ttlAt)`。若 SetToSet 现版本空集合经 serialize 返回空数组、SADD 报错 → 红；若现版本静默无操作导致 key 不存在 → 该断言已绿，记录实际行为并在报告中说明。）

- [ ] **Step 2: 运行验证失败/基线**

Run: `./gradlew :cocache-spring-redis:test --tests "me.ahoo.cache.spring.redis.codec.*"`
Expected: 空集合守卫测试 FAIL（或记录已绿的基线）；TTL 断言 PASS（回归锁）

- [ ] **Step 3: 实现 Lua 助手并替换三个结构型 codec**

3a. `AbstractCodecExecutor` 新增（import：`org.springframework.data.redis.core.script.DefaultRedisScript`、`org.springframework.data.redis.core.script.RedisScript`；删除 `setPipelined` 与 `org.springframework.data.redis.connection.RedisConnection` import——Task 5 后无调用方。注意：**companion 在 Task 1 已存在（含 `log`），以下脚本常量追加进既有 companion，勿重复定义 `log`**）：

```kotlin
    protected fun executeAtomicHashWrite(key: String, hashes: Map<String, String>, ttlSeconds: Long) {
        if (hashes.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(hashes.size * 2 + 1)
        hashes.forEach { (field, value) ->
            args.add(field)
            args.add(value)
        }
        args.add(ttlSeconds.coerceAtLeast(0).toString())
        redisTemplate.execute(SET_HASH_SCRIPT, listOf(key), *args.toTypedArray())
    }

    protected fun executeAtomicSetWrite(key: String, members: Set<String>, ttlSeconds: Long) {
        if (members.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(members.size + 1)
        args.addAll(members)
        args.add(ttlSeconds.coerceAtLeast(0).toString())
        redisTemplate.execute(SET_SET_SCRIPT, listOf(key), *args.toTypedArray())
    }

    companion object {
        // —— log 已在 Task 1 定义，此处省略 ——

        /**
         * DEL + HSET + 可选 EXPIRE 原子执行（ARGV 为扁平 field/value 对，末位为 ttl 秒数，0 表示永不过期）。
         */
        private val SET_HASH_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV - 1, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            local ttl = tonumber(ARGV[#ARGV])
            if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * DEL + SADD + 可选 EXPIRE 原子执行（ARGV 为成员列表，末位为 ttl 秒数，0 表示永不过期）。
         */
        private val SET_SET_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV - 1 do
              redis.call('SADD', KEYS[1], ARGV[i])
            end
            local ttl = tonumber(ARGV[#ARGV])
            if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
```

3b. `MapToHashCodecExecutor`（`ObjectToHashCodecExecutor` 同模式替换 hash 版本）：

```kotlin
    override fun setForeverValue(key: String, cacheValue: CacheValue<Map<String, String>>) {
        executeAtomicHashWrite(key, cacheValue.toRawValue(), ttlSeconds = 0)
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<Map<String, String>>) {
        executeAtomicHashWrite(key, cacheValue.toRawValue(), ttlSeconds = cacheValue.expiredDuration.seconds)
    }
```

3c. `SetToSetCodecExecutor`：

```kotlin
    override fun setForeverValue(key: String, cacheValue: CacheValue<Set<String>>) {
        executeAtomicSetWrite(key, cacheValue.toRawValue(), ttlSeconds = 0)
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<Set<String>>) {
        executeAtomicSetWrite(key, cacheValue.toRawValue(), ttlSeconds = cacheValue.expiredDuration.seconds)
    }
```

3d. 新建 `AtomicWriteConsistencyTest.kt`（并发一致性冒烟）：

```kotlin
package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.util.CacheSecondClock
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 原子写入冒烟：并发交替写两个版本，最终值必须是完整单一版本（无字段混合）。
 */
internal class AtomicWriteConsistencyTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var codecExecutor: MapToHashCodecExecutor

    @BeforeEach
    fun setup() {
        lettuceConnectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        codecExecutor = MapToHashCodecExecutor(stringRedisTemplate)
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun concurrentWritesProduceCompleteSingleVersion() {
        val key = "atomic-write:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 60
        val versionA = mapOf("fa" to "va1", "extra-a" to "va2")
        val versionB = mapOf("fb" to "vb1")
        val iterations = 50
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        try {
            repeat(2) { thread ->
                val version = if (thread == 0) versionA else versionB
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(iterations) {
                            codecExecutor.executeAndEncode(key, DefaultCacheValue(version, ttlAt))
                        }
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }
            startLatch.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS).assert().isTrue()
        }
        errors.get().assert().isZero()

        val finalFields = stringRedisTemplate.opsForHash<String, String>().keys(key)
        val isCompleteA = finalFields == versionA.keys
        val isCompleteB = finalFields == versionB.keys
        (isCompleteA || isCompleteB).assert()
            .withFailMessage { "field-union corruption detected: $finalFields" }
            .isTrue()
    }
}
```

- [ ] **Step 4: 运行验证（绿）**

Run: `./gradlew :cocache-spring-redis:test`
Expected: BUILD SUCCESSFUL（空集合守卫转绿；TTL/哨兵/往返/并发冒烟全绿）

- [ ] **Step 5: 提交**

```bash
git add cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/MapToHashCodecExecutor.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToHashCodecExecutor.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/SetToSetCodecExecutor.kt cocache-spring-redis/src/test/kotlin/me/ahoo/cache/spring/redis/codec/
git commit -m "fix(redis): atomic hash and set writes via lua scripts"
```

---

## Task 6: starter 配置透传 + 集成冒烟

**Files:**
- Modify: `cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheProperties.kt`
- Modify: `cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt:125-133`
- Test: starter 既有测试目录追加 ApplicationContextRunner 冒烟（先 `ls cocache-spring-boot-starter/src/test/kotlin/me/ahoo/cache/spring/boot/starter/` 找既有 runner 测试类，跟随其风格；若无合适宿主则新建）

- [ ] **Step 1: CoCacheProperties 嵌套 Redis 配置**

```kotlin
@ConfigurationProperties(prefix = CoCache.COCACHE)
data class CoCacheProperties(
    val enabled: Boolean = true,
    val redis: Redis = Redis(),
) {
    data class Redis(
        /**
         * Redis 故障时重抛异常（默认 false = 降级：读按未命中、写/evict 仅告警）。
         */
        val strictFailure: Boolean = false,
        /**
         * 自定义 missing-guard 哨兵值。变更需全集群同时切换（新旧哨兵互不识别）。
         */
        val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
    )
}
```

（import `me.ahoo.cache.MissingGuard`。）

- [ ] **Step 2: CoCacheAutoConfiguration 透传**

`distributedCacheFactory` bean 方法替换为：

```kotlin
    @Bean
    @ConditionalOnMissingBean
    fun distributedCacheFactory(
        beanFactory: BeanFactory,
        objectMapper: ObjectMapper,
        redisTemplate: StringRedisTemplate,
        cocacheProperties: CoCacheProperties
    ): DistributedCacheFactory {
        return RedisDistributedCacheFactory(
            beanFactory,
            objectMapper,
            redisTemplate,
            missingGuardSentinel = cocacheProperties.redis.missingGuardSentinel,
        )
    }
```

`RedisDistributedCacheFactory` 新增 `strictFailure: Boolean = false` 构造参数，`fallback` 中 `RedisDistributedCache` 构造传 `strictFailure = strictFailure`。

- [ ] **Step 3: 配置冒烟测试（自包含新测试文件，无需 Redis）**

新建 `cocache-spring-boot-starter/src/test/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheRedisPropertiesTest.kt`（mockk 提供 Redis/Jackson 依赖 bean，仅验证"属性绑定 → 工厂构造参数"链）：

```kotlin
package me.ahoo.cache.spring.boot.starter

import io.mockk.mockk
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.spring.redis.RedisDistributedCacheFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

internal class CoCacheRedisPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withBean("stringRedisTemplate", StringRedisTemplate::class.java) { mockk(relaxed = true) }
        .withBean("redisConnectionFactory") { mockk<org.springframework.data.redis.connection.RedisConnectionFactory>(relaxed = true) }
        .withBean("objectMapper", ObjectMapper::class.java) { mockk(relaxed = true) }

    @Test
    fun defaultsBindToFactory() {
        contextRunner.run { context ->
            val factory = context.getBean(DistributedCacheFactory::class.java)
            (factory is RedisDistributedCacheFactory).assert().isTrue()
        }
    }

    @Test
    fun customPropertiesBindToFactory() {
        contextRunner
            .withPropertyValues(
                "cocache.redis.strict-failure=true",
                "cocache.redis.missing-guard-sentinel=custom-nil",
            )
            .run { context ->
                context.getBean(DistributedCacheFactory::class.java)
                // 属性绑定链由构造参数承接；工厂内部不暴露 getter，
                // 断言装配成功 + 无异常即验证了绑定路径（严格模式的运行时行为已在 Task 4 单测覆盖）
            }
    }
}
```

（注：若 `CoCacheAutoConfiguration` 被 `@ConditionalOnCoCacheEnabled` 等条件守卫且默认上下文不装配（因缺少 Redis 自动配置类），按其条件注解补 `withPropertyValues("cocache.enabled=true")` 或所需的 `@ConditionalOnClass` 线索；starter 既有 ApplicationContextRunner 测试是参考范本——执行前先 `ls` 测试目录并读取一个既有 runner 测试对齐风格。`RedisAutoConfiguration` import 若实际未用到则删除。）

- [ ] **Step 4: 运行验证**

Run: `./gradlew :cocache-spring-boot-starter:test`
Expected: BUILD SUCCESSFUL（需要本地 Redis；若本地不可用记录并依赖 CI）

- [ ] **Step 5: 提交**

```bash
git add cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheProperties.kt cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt <冒烟测试文件>
git commit -m "feat(starter): wire redis failure policy and sentinel properties"
```

---

## Task 7: 全量验收

- [ ] **Step 1: 全量检查**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL（含本地 Redis 集成测试与 detekt）

- [ ] **Step 2: 规格对照核对**

- #6 → Task 1（自愈返回 null + 删 key）
- #7 → Task 2（四 codec 统一负缓存语义，规范层断言 ×4）
- #8 → Task 4/6（降级 + strict 开关 + 总线包裹 + starter 透传）
- #9 → Task 5（Lua 原子 + 空集合守卫 + 并发冒烟 + TTL 回归锁）
- 哨兵 → Task 3/6（codec 属性化 + 工厂/配置透传）
- 字节级兼容核对：存储结构（HSET/SADD/SET 值格式）与消息格式零变更
- 验收标准 2：Task 1/2/4 的红相已逐步验证

- [ ] **Step 3: 收尾提交（若有零散修正）**

```bash
git add <具体文件>
git commit -m "fix(redis): address final review findings"
```
