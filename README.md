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

```kotlin
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