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

description = "CoCache coherence engine, policies and in-memory backend"

dependencies {
    api(project(":cocache-api"))
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.github.oshai:kotlin-logging-jvm")
    // 协议核心用协程实现（RFC 0005 V5）：同步、异步、挂起三种变体共用一份 suspend 实现
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation(project(":cocache-test"))
    // 确定性多节点仿真：测试调度器与虚拟时间（RFC 0005 §4.10）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

tasks.withType<Test> {
    // 确定性多节点仿真只重放一个种子：./gradlew :cocache-core:test --tests "*MultiNodeSimulationTest" -PsimSeed=<n>
    providers.gradleProperty("simSeed").orNull?.let { systemProperty("cocache.sim.seed", it) }
}
