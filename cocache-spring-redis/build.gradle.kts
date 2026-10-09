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

plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    api(project(":cocache-core"))
    api(project(":cocache-spring"))
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.springframework.data:spring-data-redis")
    testImplementation(project(":cocache-test"))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("io.lettuce:lettuce-core")
    jmh(platform(project(":cocache-dependencies")))
    jmh("io.lettuce:lettuce-core")
    jmh("ch.qos.logback:logback-classic")
}

/**
 * 命中路径基准（需要 localhost:6379 的 Redis），不属于 check：
 * `./gradlew :cocache-spring-redis:jmh -PjmhThreads=8 -PjmhIncludes=l2Hit`
 */
jmh {
    jmhVersion = libs.versions.openjdk.jmh
    fork = 1
    warmupIterations = 3
    warmup = "3s"
    iterations = 5
    timeOnIteration = "5s"
    threads = providers.gradleProperty("jmhThreads").map(String::toInt).orElse(1)
    includes = providers.gradleProperty("jmhIncludes").map { it.split(',') }.orElse(emptyList())
    resultFormat = "JSON"
    jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-jmh.xml")
}

// 基准源码随 check 编译，防止腐化；运行基准需显式执行 jmh 任务。
tasks.named("check") {
    dependsOn("jmhClasses")
}
