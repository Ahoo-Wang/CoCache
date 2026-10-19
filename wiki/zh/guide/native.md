---
title: 原生镜像
description: 在经过 Spring AOT 处理的应用与 GraalVM 原生镜像中运行 CoCache：如何构建、starter 自动登记了哪些提示、哪些提示仍需自己提供。
---

# 原生镜像

CoCache 可以在经过 Spring AOT 处理的应用中运行，无论是在 JVM 上还是作为 GraalVM 原生镜像。starter 让自己注册的缓存 bean 能由 AOT 生成的代码重建，并为它自己创建的一切登记运行时提示：代理、反射与 Jackson 绑定。按 Spring Boot 的常规方式构建镜像，只为你自己写的代码补充提示。

## 构建原生镜像 {#build}

按 Spring Boot 的标准方式配置。Spring Boot 4 需要 GraalVM 25 及以上。Native Build Tools 使用你的 Spring Boot 版本所管理的版本（`spring-boot-dependencies` 中的 `native-build-tools-plugin.version` 属性）。

::: code-group

```kotlin [Gradle (Kotlin)]
plugins {
    id("org.springframework.boot") version "<spring-boot-version>"
    id("org.graalvm.buildtools.native") version "<native-build-tools-version>"
}
```

```xml [Maven]
<!-- 使用 spring-boot-starter-parent -->
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
</plugin>
```

:::

然后运行 `./gradlew nativeCompile`（Maven：`mvn -Pnative native:compile`）。CoCache 不需要任何额外配置。

[`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example) 在 CI 中就是这样构建的（[`native.yml`](https://github.com/Ahoo-Wang/CoCache/blob/main/.github/workflows/native.yml)）。该任务在 Redis 下启动可执行文件，依次调用接口式缓存、spec 缓存、带 `joinKeyExpression` 的 JoinCache、`@Cacheable(sync = true)` 与 actuator 端点。

## AOT 在构建期固定的内容 {#build-time}

AOT 在构建期执行应用的 bean 注册，并把结果写成生成代码。因此 `@EnableCoCache` 背后的注册器与 spec bean 的注册器只在构建时运行一次。启动时由生成的代码注册同样的缓存 bean，注册器不再运行。

构建时固定：

- `@EnableCoCache(caches = …, joinCaches = …)` 声明的缓存，以及 spec bean。
- 所有 `@Conditional` 的结果，与任何 Spring Boot AOT 应用一样。其中包括 `cocache.enabled` 与 `cocache.backend`，修改后需要重新构建。

启动时仍会读取：

- 策略配置项：`cocache.defaults.*`、`cocache.caches.[name].*`、`cocache.redis.*`，以及执行器的 bean 名。
- `CacheSpecCustomizer` bean、loader、编解码与 Redis 连接配置。

## CoCache 登记的提示 {#registered-hints}

构建时 starter 读取缓存的 bean 定义并登记下列提示，你不需要写其中任何一个。

| 对象 | 提示 | 原因 |
|------|------|------|
| 每个 `@EnableCoCache(caches)` 与 `joinCaches` 接口 | 只包含该接口的 JDK 代理 | 缓存就是实现该接口的代理 |
| 同样这些接口 | 调用公共方法 | 启动时读取注解与泛型、校验抽象方法，default 方法经 `InvocationHandler.invokeDefault` 执行 |
| 每个已声明缓存的 key 类型（含 spec bean） | `toString()` | 默认 key 编码器对每种 key 类型检查一次是否重写了 `toString()` |
| 设置了 `@CoCache(keyExpression)` 时的 key 类型 | 公共方法与字段 | SpEL 以 key 为根对象求值模板 |
| 设置了 `@JoinCacheable(joinKeyExpression)` 时主缓存的值类型 | 公共方法与字段 | SpEL 以主缓存的值为根对象求值表达式 |
| 没有指定编解码的接口式缓存的值类型（`SetCache` 为元素类型） | Jackson 绑定 | 该缓存使用默认的 `JacksonCodec` |
| `Cache`、`SetCache`、`JoinCache` | 公共方法 | 代理分派与校验 |
| `AsyncCache`、`AsyncSetCache` | 公共方法 | `cache.async()` 返回的[异步视图](./async.md) |
| L2 背后的 Caffeine 类 | 构造器与字段 | Caffeine 按类名加载其生成的类 |
| `Executors.newVirtualThreadPerTaskExecutor` | 调用 | JDK 支持虚拟线程时，运行时的默认阻塞执行器使用虚拟线程 |

接口式缓存“没有指定编解码”指既没有 `@CoCache(codec)`，也没有与其值类型匹配的 `Codec<V>` bean，与 starter 选择编解码的规则一致（[编解码](./caches.md#codecs)）。

## 需要你提供的提示 {#your-hints}

starter 看不到你自己写的代码内部。下列情况需要自己登记提示：

- **使用 Jackson 的 spec 缓存。** spec 自己构造编解码，CoCache 不知道它序列化的值类型。登记绑定：

  ```kotlin
  @Configuration
  @RegisterReflectionForBinding(Product::class)
  class ProductCacheConfiguration {
      @Bean
      fun productSpec(mapper: ObjectMapper): CacheSpec<String, Product> = cacheSpec("product") {
          codec = jacksonCodec<Product>(mapper)
      }
  }
  ```

- **多态的值。** 绑定提示覆盖声明的值类型及其属性可达的类型。如果值类型是抽象类或密封类、由 Jackson 在运行时选择子类型，把子类型也列在 `@RegisterReflectionForBinding` 中。
- **自定义编解码。** 使用反射的 `Codec`（例如另一个序列化库）需要该库为你的值类型准备的提示。
- **离开根对象的 SpEL。** 提示只覆盖根对象的公共成员。`#{owner.address.city}` 这样的模板还会读取 `Address`，`T(…)` 会调用其它类的静态方法：用 `@RegisterReflection(classes = [Address::class], memberCategories = [MemberCategory.INVOKE_PUBLIC_METHODS])` 或 `RuntimeHintsRegistrar` 登记这些类型。热点路径上，使用 `toString()` 合适的 key 类型可以完全避开 SpEL（[key](./caches.md#keys)）。
- **不经 starter 创建的缓存。** 在自己的代码中调用 `CacheRuntime.create` 与 `CacheProxies.create` 不会得到生成的提示：自己登记代理接口与 key 类型的 `toString()`。

loader、`TtlFunction`、`KeyEncoder` 与 `JoinKeyExtractor` bean 都是普通的函数调用，不需要提示。

## 编译之前先检查 {#verify}

原生构建需要几分钟。先在 JVM 上运行经过 AOT 处理的应用，大多数问题能更早暴露：

```bash
./gradlew bootJar
java -Dspring.aot.enabled=true -jar build/libs/app.jar
```

这会在没有注册器的情况下执行生成的 bean 注册，AOT 无法重建的缓存会在这里失败。缺失的反射或代理提示只在原生镜像中暴露，用 `./gradlew nativeTest` 在原生镜像中运行测试。
