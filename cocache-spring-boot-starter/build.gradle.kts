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

description = "CoCache Spring Boot auto-configuration, actuator endpoint and metrics"

plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}
dependencies {
    kapt(platform(project(":cocache-dependencies")))
    api(project(":cocache-spring"))
    api(project(":cocache-redis"))
    api(project(":cocache-codec-jackson"))
    api("org.springframework.boot:spring-boot-starter-jackson")
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-data-redis")
    // Actuator 端点按 @ConditionalOnClass 激活：编译期可见，由使用方自行引入
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    // Micrometer 指标按 @ConditionalOnClass 激活：编译期可见，由使用方自行引入
    compileOnly("io.micrometer:micrometer-core")
    // 回源追踪按 @ConditionalOnClass(ObservationRegistry) 激活（micrometer-core 依赖它，这里显式声明编译期依赖）
    compileOnly("io.micrometer:micrometer-observation")
    testImplementation("io.micrometer:micrometer-observation-test")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // AOT 测试：编译 ApplicationContextAotGenerator 生成的源码并在新容器中运行
    testImplementation("org.springframework:spring-core-test")
    // 校验原生镜像提示中的 Caffeine 生成类名（cocache-core 的 implementation 依赖，测试编译期需要可见）
    testImplementation("com.github.ben-manes.caffeine:caffeine")
    testImplementation(project(":cocache-test"))
}

kapt {
    arguments {
        // kapt 的类输出目录不在 Gradle 的标准位置，显式告诉配置处理器去哪里找 additional-spring-configuration-metadata.json
        arg("org.springframework.boot.configurationprocessor.additionalMetadataLocations", "$projectDir/src/main/resources")
    }
}

tasks.matching { it.name == "kaptKotlin" }.configureEach {
    inputs.file("src/main/resources/META-INF/additional-spring-configuration-metadata.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
