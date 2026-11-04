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
    // 协议核心用协程实现（RFC 0005 V5）：同步、异步、挂起三种变体共用一份 suspend 实现。
    // api：挂起的数据源（SuspendingCacheLoader）与挂起视图的使用者都要用到它（版本由 Spring Boot 的 BOM 管理）
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation(project(":cocache-test"))
    // 确定性多节点仿真：测试调度器与虚拟时间（RFC 0005 §4.10）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // 模型检验（RFC 0005 §4.10）：flight 层 + L2 + 失效戳
    testImplementation("org.jetbrains.lincheck:lincheck")
}

tasks.withType<Test> {
    // 协程的调试模式（-ea 时自动开启）会把线程名改为“线程名 @coroutine#N”，并在恢复时复制异常以补全堆栈；
    // TCK 按线程名编排钩子、断言原始异常实例，因此关闭（生产环境不开 -ea 时本来就是关闭的）
    systemProperty("kotlinx.coroutines.debug", "off")
    systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
    // 确定性多节点仿真只重放一个种子：./gradlew :cocache-core:test --tests "*MultiNodeSimulationTest" -PsimSeed=<n>
    providers.gradleProperty("simSeed").orNull?.let { systemProperty("cocache.sim.seed", it) }
}

// Lincheck 模型检验（RFC 0005 §4.10）单独运行，不在 check 中（约 5 分钟）：./gradlew :cocache-core:lincheck
// JaCoCo 的探针与 kotlinx 调试模式的协程编号都会被模型检验当作共享状态，执行因此无法重放，所以这里两者都关掉
val lincheckTests = "*LincheckTest"
tasks.test {
    filter { excludeTestsMatching(lincheckTests) }
}
tasks.register<Test>("lincheck") {
    description = "Runs the Lincheck model checks of the flight layer, L2 and invalidation stamps."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(lincheckTests) }
    systemProperty("kotlinx.coroutines.debug", "off")
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    // 模型检验在一个核上依次运行各个线程：每个测试类一个 JVM，至多 4 个并行，空闲的 JVM 接着取下一个类，
    // 墙钟时间取决于最慢的类（CoherentEngineLincheckTest）
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    forkEvery = 1
}
