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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "CoCache"

// 目录名不带 `cocache-` 前缀；项目名（也是发布的 artifactId 与 Gradle 任务路径）保留前缀
listOf(
    "bom",
    "dependencies",
    "api",
    "core",
    "spring",
    "coroutines",
    "redis",
    "codec-jackson",
    "spring-boot-starter",
    "test",
    "example",
).forEach { dir ->
    include(":cocache-$dir")
    project(":cocache-$dir").projectDir = file(dir)
}
include(":code-coverage-report")
