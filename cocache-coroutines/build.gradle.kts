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

description = "CoCache Kotlin coroutines: suspending views over the async API"

dependencies {
    api(project(":cocache-core"))
    // 只出现在本模块（RFC 0004 §7，A12）：cocache-api、cocache-core 与其它模块不依赖它
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation(project(":cocache-test"))
}

tasks.withType<Test> {
    // 协程的调试模式（-ea 时自动开启）会把线程名改为“线程名 @coroutine#N”，并在恢复时复制异常以补全堆栈；
    // TCK 按线程名编排钩子、断言原始异常实例，因此关闭（生产环境不开 -ea 时本来就是关闭的）
    systemProperty("kotlinx.coroutines.debug", "off")
    systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
}
