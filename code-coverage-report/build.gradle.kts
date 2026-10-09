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
    base
    id("jacoco-report-aggregation")
}

@Suppress("UNCHECKED_CAST")
val libraryProjects = rootProject.ext.get("libraryProjects") as Iterable<Project>

dependencies {
    libraryProjects.forEach {
        jacocoAggregation(it)
    }
}

reporting {
    reports {
        val codeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

/**
 * 覆盖率门禁：低于阈值则构建失败（Codecov 在 PR 上做同样的增量控制）。
 */
val codeCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Fails the build when aggregated coverage drops below the project thresholds."
    val report = tasks.named<JacocoReport>("codeCoverageReport")
    dependsOn(report)
    executionData.from(report.map { it.executionData })
    classDirectories.from(report.map { it.classDirectories })
    sourceDirectories.from(report.map { it.sourceDirectories })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("codeCoverageReport"), codeCoverageVerification)
}
