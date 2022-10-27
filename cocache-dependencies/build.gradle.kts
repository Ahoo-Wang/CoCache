/*
 * Copyright [2021-2021] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:2.7.5"))
    api(platform("org.springframework.cloud:spring-cloud-dependencies:2021.0.4"))
    api(platform("me.ahoo.cosid:cosid-bom:1.14.8"))
    constraints {
        api("com.google.guava:guava:31.1-jre")
        api("org.junit-pioneer:junit-pioneer:1.7.1")
        api("org.hamcrest:hamcrest:2.2")
        api("io.mockk:mockk:1.13.2")
        api("org.openjdk.jmh:jmh-core:1.34")
        api("org.openjdk.jmh:jmh-generator-annprocess:1.34")
        api("io.gitlab.arturbosch.detekt:detekt-formatting:1.21.0")
    }
}
