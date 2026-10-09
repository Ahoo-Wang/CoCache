# CoCache
Level 2 Distributed Coherence Cache Framework

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/CoCache.svg)](https://github.com/Ahoo-Wang/CoCache/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.cocache/cocache-core)](https://central.sonatype.com/artifact/me.ahoo.cocache/cocache-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/a2f3fd9b1e564fa3a3b558d1dfaf2a34)](https://www.codacy.com/gh/Ahoo-Wang/CoCache/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/CoCache&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/CoCache/branch/main/graph/badge.svg?token=NlFI44RCS4)](https://codecov.io/gh/Ahoo-Wang/CoCache)
[![CI](https://github.com/Ahoo-Wang/CoCache/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/CoCache/actions/workflows/ci.yml)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/CoCache)

> [中文文档](https://cocache.ahoo.me/zh/) | [English Document](https://cocache.ahoo.me/)

## Architecture

<p align="center" style="text-align:center">
  <img src="document/Architecture.png" alt="Architecture"/>
</p>

## Installation

> Use *Gradle(Kotlin)* to install dependencies

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter")
```

> Use *Gradle(Groovy)* to install dependencies

```groovy
implementation 'me.ahoo.cocache:cocache-spring-boot-starter'
```

> Use *Maven* to install dependencies

```xml
<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
    <version>${cocache.version}</version>
</dependency>
```

## Usage

```mermaid
classDiagram
direction BT
class Cache~K, V~ {
  <<Interface>>
  + getCache(K) CacheValue~V~?
  + get(K) V?
  + getTtlAt(K) Long?
  + setCache(K, CacheValue~V~) Unit
  + set(K, V) Unit
  + set(K, Long, V) Unit
  + evict(K) Unit
}
class CacheValue~V~ {
  <<sealed>>
  + ttlAt Long
  + value V?
  + isMissing Boolean
}
class PresentValue~V~
class MissingValue
class CacheSource~K, V~ {
  <<Interface>>
  + loadCacheValue(K) CacheValue~V~?
}
class UserCache {
  <<Interface>>
}
PresentValue~V~ ..|> CacheValue~V~
MissingValue ..|> CacheValue~V~
UserCache --|> Cache~K, V~
UserCacheSource ..|> CacheSource~K, V~
```

```kotlin
/**
 * Declare a cache interface; CoCache generates the implementation.
 * ttl/ttlAmplitude/missingTtl are seconds (defaults: 3600 / 60 / 60).
 */
@CoCache(keyPrefix = "user:", ttl = 120)
/**
 * Optional: L2 (Caffeine) settings. L2 is always bounded.
 */
@CaffeineCache(maximumSize = 1_000_000, expireAfterAccess = 120)
interface UserCache : Cache<String, User>

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer

/**
 * Optional customization. Stateful components are resolved by bean name
 * `{cacheName}.ClientSideCache | .DistributedCache | .KeyConverter`;
 * a CacheSource may also be resolved by its generic type.
 */
@Configuration
class UserCacheConfiguration {
    @Bean("UserCache.ClientSideCache")
    fun userClientSideCache(): ClientSideCache<User> {
        return CaffeineClientSideCache.build(maximumSize = 100_000)
    }

    @Bean
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> {
        // returning null writes a negative cache for `missingTtl` seconds
        return CacheSource { id -> userRepository.findById(id)?.let { CacheValue.forever(it) } }
    }
}
```

## Consistency Guarantees

- **Bounded staleness**: values expire after `ttl` (finite by default); negative cache entries after `missingTtl`; every (re)subscription of the eviction channel clears L2, so lost pub/sub messages cannot leave L2 stale.
- **No lost invalidations**: concurrent loads are coalesced per key, and every write-back (L1 → L2 fill, source load) is discarded or undone if an eviction happened meanwhile — local or remote.
- **Write pattern**: update the data source first, then `evict(key)` (or `set` the new value).

See [`docs/architecture.md`](docs/architecture.md) for the full design and invariants.

## CoCache `Get` Sequence Diagram

<p align="center" style="text-align:center">
  <img src="document/CoCache-Get-Sequence-Diagram.svg" alt="CoCache-Get-Sequence-Diagram"/>
</p>

## JoinCache `Get` Sequence Diagram

<p align="center" style="text-align:center">
  <img src="document/JoinCache.svg" alt="JoinCache-Get-Sequence-Diagram"/>
</p>
## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the branching model, quality gates, and release process. Report security issues privately per [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
