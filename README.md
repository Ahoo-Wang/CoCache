# CoCache
Level 2 Distributed Coherence Cache Framework

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/CoCache.svg)](https://github.com/Ahoo-Wang/CoCache/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cocache/cocache-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cocache/cocache-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/a2f3fd9b1e564fa3a3b558d1dfaf2a34)](https://www.codacy.com/gh/Ahoo-Wang/CoCache/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/CoCache&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/CoCache/branch/main/graph/badge.svg?token=NlFI44RCS4)](https://codecov.io/gh/Ahoo-Wang/CoCache)
![Integration Test Status](https://github.com/Ahoo-Wang/CoCache/actions/workflows/integration-test.yml/badge.svg)

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
    <version>${coapi.version}</version>
</dependency>
```

## Usage

```mermaid
classDiagram
direction BT
class Cache~K, V~ {
<<Interface>>
  + getCache(K) CacheValue~V~?
  + set(K, Long, V) Unit
  + set(K, V) Unit
  + set(K, Long, V) Unit
  + get(K) V?
  + getTtlAt(K) Long?
  + set(K, V) Unit
  + setCache(K, CacheValue~V~) Unit
  + get(K) V?
  + getTtlAt(K) Long?
  + evict(K) Unit
}
class CacheGetter~K, V~ {
<<Interface>>
  + get(K) V?
}
class CacheSource~K, V~ {
<<Interface>>
  + load(K) CacheValue~V~?
  + noOp() CacheSource~K, V~
}
class UserCache {
  + set(String, UserData) Unit
  + setCache(String, CacheValue~UserData~) Unit
  + getCache(String) CacheValue~UserData~?
  + evict(String) Unit
  + get(String) UserData?
  + getTtlAt(String) Long?
  + set(String, Long, UserData) Unit
}
class UserCacheAutoConfiguration {
  + userCache(CacheSource~String, UserData~, StringRedisTemplate, CacheManager, ClientIdGenerator) UserCache
  + userCacheSource(UserClient) CacheSource~String, UserData~
}
class UserCacheSource {
  + load(String) CacheValue~UserData~?
}

Cache~K, V~  -->  CacheGetter~K, V~ 
UserCache  ..>  Cache~K, V~ 
UserCacheSource  ..>  CacheSource~K, V~ 

```

```kotlin

class UserCache(private val delegate: Cache<String, UserData>) :
    Cache<String, UserData> by delegate

@AutoConfiguration
class UserCacheAutoConfiguration {
    companion object {
        const val CACHE_KEY_PREFIX = "iam"
        const val USER_CACHE_BEAN_NAME = "userCache"
        const val USER_CACHE_SOURCE_BEAN_NAME = "${USER_CACHE_BEAN_NAME}Source"
    }

    @Bean
    @ConditionalOnMissingBean(name = [USER_CACHE_SOURCE_BEAN_NAME])
    fun userCacheSource(userClient: UserClient): CacheSource<String, UserData> {
        return UserCacheSource(userClient)
    }

    @Bean
    @ConditionalOnMissingBean
    fun userCache(
        @Qualifier(USER_CACHE_SOURCE_BEAN_NAME) cacheSource: CacheSource<String, UserData>,
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        clientIdGenerator: ClientIdGenerator
    ): UserCache {
        val clientId = clientIdGenerator.generate()
        val cacheKeyPrefix = "$CACHE_KEY_PREFIX:user:"
        val codecExecutor = ObjectToJsonCodecExecutor(UserData::class.java, redisTemplate, JsonSerializer)
        val distributedCaching: DistributedCache<UserData> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = USER_CACHE_BEAN_NAME,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheKeyPrefix),
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        return UserCache(delegate)
    }
}
```

## CoCache `Get` Sequence Diagram

<p align="center" style="text-align:center">
  <img src="document/CoCache-Get-Sequence-Diagram.svg" alt="CoCache-Get-Sequence-Diagram"/>
</p>

## JoinCache `Get` Sequence Diagram

<p align="center" style="text-align:center">
  <img src="document/JoinCache.svg" alt="JoinCache-Get-Sequence-Diagram"/>
</p>