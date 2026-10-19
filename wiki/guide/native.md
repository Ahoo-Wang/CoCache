---
title: Native Images
description: Run CoCache in Spring AOT-processed applications and GraalVM native images — how to build, what the starter registers for you, and which hints you still provide yourself.
---

# Native Images

CoCache works in applications processed by Spring AOT, on the JVM and as GraalVM native images. The starter makes its cache beans reproducible by AOT-generated code and contributes the runtime hints for everything it creates itself: proxies, reflection, and Jackson bindings. You build the image the usual Spring Boot way and add hints only for code that you write.

## Building a native image {#build}

Follow Spring Boot's standard setup. Spring Boot 4 needs GraalVM 25 or later. Use the Native Build Tools version that your Spring Boot release manages (the `native-build-tools-plugin.version` property of `spring-boot-dependencies`).

::: code-group

```kotlin [Gradle (Kotlin)]
plugins {
    id("org.springframework.boot") version "<spring-boot-version>"
    id("org.graalvm.buildtools.native") version "<native-build-tools-version>"
}
```

```xml [Maven]
<!-- with spring-boot-starter-parent -->
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
</plugin>
```

:::

Then run `./gradlew nativeCompile` (Maven: `mvn -Pnative native:compile`). Nothing in CoCache needs extra configuration.

[`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example) is built this way in CI ([`native.yml`](https://github.com/Ahoo-Wang/CoCache/blob/main/.github/workflows/native.yml)). The job starts the executable against Redis and calls an interface cache, a spec cache, a JoinCache with `joinKeyExpression`, `@Cacheable(sync = true)`, and the actuator endpoint.

## What AOT fixes at build time {#build-time}

AOT runs the application's bean registration at build time and turns the result into generated code. The registrars behind `@EnableCoCache` and the spec-bean registrar therefore run once, during the build. At startup the generated code registers the same cache beans, and the registrars don't run.

Fixed when you build:

- The caches declared in `@EnableCoCache(caches = …, joinCaches = …)`, and the spec beans.
- Every `@Conditional` result, as with any Spring Boot AOT application. This includes `cocache.enabled` and `cocache.backend`. Change them and rebuild.

Still read at startup:

- Policy properties: `cocache.defaults.*`, `cocache.caches.[name].*`, `cocache.redis.*`, and the executor bean names.
- `CacheSpecCustomizer` beans, loaders, codecs, and the Redis connection settings.

## What CoCache registers {#registered-hints}

At build time the starter reads the cache bean definitions and registers these hints. You don't write any of them.

| For | Hint | Why |
|-----|------|-----|
| Each `@EnableCoCache(caches)` and `joinCaches` interface | JDK proxy for exactly that interface | The cache is a proxy implementing the interface |
| The same interfaces | Invoke public methods | Annotations and generic types are read at startup, abstract methods are validated, and default methods run through `InvocationHandler.invokeDefault` |
| The key type of every declared cache, spec beans included | `toString()` | The default key encoder checks once per key type that `toString()` is overridden |
| The key type, when `@CoCache(keyExpression)` is set | Public methods and fields | SpEL evaluates the template with the key as root object |
| The first cache's value type, when `@JoinCacheable(joinKeyExpression)` is set | Public methods and fields | SpEL evaluates the expression with the first value as root object |
| The value type (element type for a `SetCache`) of an interface cache without a codec | Jackson binding | That cache uses the default `JacksonCodec` |
| `Cache`, `SetCache`, `JoinCache` | Public methods | Proxy dispatch and validation |
| The Caffeine classes behind L2 | Constructors and fields | Caffeine loads its generated classes by name |
| `Executors.newVirtualThreadPerTaskExecutor` | Invoke | The default Spring Cache retrieve executor uses virtual threads when the JDK has them |

An interface cache counts as "without a codec" when it has no `@CoCache(codec)` and no bean of type `Codec<V>` matches its value type, which are the same rules the starter uses to pick the codec ([Codecs](./caches.md#codecs)).

## What you provide {#your-hints}

The starter can't see inside code you write. Register hints yourself for:

- **Spec caches with Jackson.** A spec builds its own codec, so CoCache doesn't know the value type it serializes. Register the binding:

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

- **Polymorphic values.** The binding hint covers the declared value type and the types reachable from its properties. If the value type is abstract or sealed and Jackson picks the subtype at runtime, list the subtypes in `@RegisterReflectionForBinding` too.
- **Custom codecs.** A `Codec` that uses reflection (another serializer, for example) needs that library's hints for your value types.
- **SpEL that leaves the root object.** The hints cover the public members of the root object only. A template such as `#{owner.address.city}` also reads `Address`, and `T(…)` calls static methods on another class: register those types with `@RegisterReflection(classes = [Address::class], memberCategories = [MemberCategory.INVOKE_PUBLIC_METHODS])` or a `RuntimeHintsRegistrar`. On a hot path, a key type with a suitable `toString()` avoids SpEL entirely ([Keys](./caches.md#keys)).
- **Caches created without the starter.** `CacheRuntime.create` and `CacheProxies.create` called from your own code get no generated hints: register the proxy interface and the key type's `toString()` yourself.

Loaders, `TtlFunction`s, `KeyEncoder`s, and `JoinKeyExtractor` beans are plain function calls and need nothing.

## Checking before you compile {#verify}

A native build takes minutes. Most problems show up sooner if you run the AOT-processed application on the JVM first:

```bash
./gradlew bootJar
java -Dspring.aot.enabled=true -jar build/libs/app.jar
```

This runs the generated bean registration without the registrars, so a cache that AOT can't recreate fails here. Missing reflection or proxy hints show up only in the native image. Run your tests there with `./gradlew nativeTest`.
