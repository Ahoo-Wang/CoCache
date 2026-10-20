---
title: 快速上手
description: 五分钟为 Spring Boot 应用加上二级一致性缓存，确认它在工作，并了解不用 Spring 时如何创建缓存。
---

# 快速上手

本页带一个 Spring Boot 应用从没有缓存走到一个可用的二级缓存。代码基于 [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example)。

**你需要** JDK 17+、Spring Boot 4，以及 Redis 7+；也可以什么都不装，用第 2 步的内存后端。

## 1. 添加依赖 {#dependencies}

::: code-group

```kotlin [Gradle (Kotlin)]
dependencies {
    implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.5.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // 可选：cocache 端点
}
```

```groovy [Gradle (Groovy)]
dependencies {
    implementation 'me.ahoo.cocache:cocache-spring-boot-starter:6.5.0'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator' // 可选：cocache 端点
}
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
    <version>6.5.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- 可选：cocache 端点 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

:::

如果用到 starter 之外的模块（例如用于 [Kotlin 协程](./coroutines.md) 的可选模块 `me.ahoo.cocache:cocache-coroutines`，6.4+），可以引入 BOM `me.ahoo.cocache:cocache-bom` 统一管理所有 CoCache 模块的版本。

## 2. 连接 Redis {#redis}

CoCache 读取 Spring Boot 的 Redis 连接配置，并建立**自己的** Lettuce 连接，从不触碰应用的连接工厂。

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

本机没有 Redis？改用内存后端：

```yaml
cocache:
  backend: memory   # 仅限单进程：用于本地开发与测试
```

## 3. 声明缓存 {#declare}

缓存是一个继承 `Cache<K, V>` 的接口。把它列在 `@EnableCoCache` 中：

::: code-group

```kotlin [Kotlin]
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.spring.boot.EnableCoCache
import org.springframework.boot.autoconfigure.SpringBootApplication

data class User(val id: String, val name: String)

@CoCache(ttl = 600)                  // Redis 中的寿命，单位秒；其余全用默认值
interface UserCache : Cache<String, User>

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

```java [Java]
// User.java
public record User(String id, String name) {}

// UserCache.java
import me.ahoo.cocache.api.Cache;
import me.ahoo.cocache.api.annotation.CoCache;

@CoCache(ttl = 600)
public interface UserCache extends Cache<String, User> {}

// App.java
import me.ahoo.cocache.spring.boot.EnableCoCache;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableCoCache(caches = UserCache.class)
@SpringBootApplication
public class App {}
```

:::

缓存名取接口名（`UserCache`），bean 名也是它。值以 `cocache:UserCache:{id}` 为 key 存入 Redis，用应用的 Jackson `ObjectMapper` 编码为 JSON。

## 4. 提供 loader {#loader}

两级都未命中时，CoCache 调用缓存的 `CacheLoader`。把它声明为 bean，按泛型类型匹配：

::: code-group

```kotlin [Kotlin]
import me.ahoo.cocache.api.CacheLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CacheConfig {
    @Bean
    fun userLoader(repo: UserRepository): CacheLoader<String, User> =
        CacheLoader { id -> repo.findById(id) }   // null 表示不存在，写入负缓存
}
```

```java [Java]
import me.ahoo.cocache.api.CacheLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CacheConfig {
    @Bean
    CacheLoader<String, User> userLoader(UserRepository repo) {
        return id -> repo.findById(id).orElse(null);   // null 表示不存在
    }
}
```

:::

## 5. 读取、写入、失效 {#use}

像注入其它 bean 一样注入这个接口：

```kotlin
@Service
class UserService(
    private val userCache: UserCache,
    private val repo: UserRepository,
    private val invalidator: TransactionalInvalidator,
) {
    fun find(id: String): User? = userCache[id]       // L2 → Redis → loader

    @Transactional
    fun rename(id: String, name: String) {
        repo.rename(id, name)                         // 1. 修改数据源
        invalidator.invalidate(userCache, id)         // 2. 提交之后失效
    }
}
```

`TransactionalInvalidator` 在事务提交之后执行失效，回滚时不执行。如果在提交之前失效，并发的读取可能把旧数据重新加载进缓存。没有事务时它立即失效。[保持数据新鲜](./freshness.md)解释了这条规则与其它做法。

## 6. 确认它在工作 {#check}

暴露 actuator 端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache
```

调用两次 `find("42")`，然后：

```bash
curl localhost:8080/actuator/cocache/UserCache        # localSize、loads 与各项计数
curl localhost:8080/actuator/cocache/UserCache/42     # 这个 key 在 L2 与 Redis 中的状态
```

第一次读取未命中并加载了一次（`loads: 1`），第二次是 L2 命中。key 视图展示本地副本及其到期时间、Redis 副本及其剩余寿命，而且不会触发任何加载。再起一个连接同一 Redis 的实例，在其中一个上调用 `rename`，另一个的下一次读取就会返回新名字。

## 不用 Spring {#without-spring}

引入 `cocache-core`、`cocache-redis` 与 `cocache-codec-jackson`（版本相同）。每个进程创建一个 `CacheRuntime`，再按声明创建缓存：

::: code-group

```kotlin [Kotlin]
import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.redis.RedisBackend
import tools.jackson.databind.json.JsonMapper   // Jackson 3
import java.time.Duration

val mapper = JsonMapper.builder().findAndAddModules().build()
val backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
val runtime = CacheRuntime.builder().backend(backend).build()

val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("UserCache") {
        codec = jacksonCodec<User>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }
)

// 关闭时：先关缓存，再关后端
runtime.close()
backend.close()
```

```java [Java]
import io.lettuce.core.RedisURI;
import java.time.Duration;
import me.ahoo.cocache.api.Cache;
import me.ahoo.cocache.codec.jackson.JacksonCodec;
import me.ahoo.cocache.core.CacheRuntime;
import me.ahoo.cocache.core.CacheSpec;
import me.ahoo.cocache.redis.RedisBackend;
import tools.jackson.databind.ObjectMapper;        // Jackson 3
import tools.jackson.databind.json.JsonMapper;

ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
RedisBackend backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"));
CacheRuntime runtime = CacheRuntime.builder().backend(backend).build();

Cache<String, User> users = runtime.create(
    CacheSpec.<String, User>builder("UserCache")
        .codec(JacksonCodec.of(mapper, User.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy.ttl(Duration.ofMinutes(10)))
        .build()
);

runtime.close();
backend.close();
```

:::

## 下一步 {#next}

- [工作原理](./concepts.md)：读取、写入与陈旧度背后的心智模型。
- [声明缓存](./caches.md)：key、loader、编解码、spec bean 与集合缓存。
- [配置参考](./configuration.md)：所有 `cocache.*` 配置项。
