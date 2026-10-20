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

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    jacoco
}

val dependenciesProject = project(":cocache-dependencies")
val bomProjects = setOf(
    project(":cocache-bom"),
    dependenciesProject,
)

val coreProjects = setOf(
    project(":cocache-core"),
)

val serverProjects = setOf(
    project(":cocache-example"),
)

val testProject = project(":cocache-test")
val codeCoverageReportProject = project(":code-coverage-report")
val publishProjects = subprojects - serverProjects - codeCoverageReportProject
val libraryProjects = publishProjects - bomProjects

ext.set("libraryProjects", libraryProjects)

// 汇总所有模块的 Detekt SARIF，供 GitHub code scanning 上传
val detektReportMerge = tasks.register<ReportMergeTask>("detektReportMerge") {
    output.set(rootProject.layout.buildDirectory.file("reports/detekt/merge.sarif"))
    input.from(allprojects.map { p -> p.tasks.withType<Detekt>().map { it.sarifReportFile } })
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
        basePath = rootDir.absolutePath
    }
    tasks.withType<Detekt>().configureEach {
        reports.sarif.required.set(true)
        finalizedBy(detektReportMerge)
    }
    dependencies {
        detektPlugins(project(dependenciesProject.path))
        detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting")
    }
    tasks.withType<Jar> {
        manifest {
            attributes["Implementation-Title"] = project.name
            attributes["Implementation-Version"] = project.version
        }
    }
}

configure(bomProjects) {
    apply<JavaPlatformPlugin>()
    configure<JavaPlatformExtension> {
        allowDependencies()
    }
}

configure(libraryProjects) {
    apply<DokkaPlugin>()
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    configure<JavaPluginExtension> {
        withJavadocJar()
    }
    // javadoc jar 放 Dokka 生成的 HTML（Java 的 javadoc 读不了 Kotlin 源码，否则是空 jar）
    tasks.named<Jar>("javadocJar") {
        from(tasks.named("dokkaGeneratePublicationHtml"))
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // 必须在应用 Kotlin 插件之后：之前调用会让 .module 重复登记 sources jar；不调用则不发布 sources jar（Central 会拒绝）
    configure<JavaPluginExtension> {
        withSourcesJar()
    }
    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        // 与 apiVersion 一致：POM 中声明的 kotlin-stdlib 不高于用户的 Kotlin 编译器能读取的版本
        coreLibrariesVersion = "2.2.21"
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict", "-jvm-default=enable")
            javaParameters = true
            // 产物的元数据与标准库 API 固定在 Spring Boot 4 支持的最低 Kotlin 版本，用户用较老的 Kotlin 编译器与 stdlib 也能使用
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
    }
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters"))
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        // fix logging missing code for JacocoPlugin
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
        // 产物始终按 JDK 17 编译；-PtestJavaVersion=25 让测试运行在更新的 JDK 上，验证运行时兼容性
        providers.gradleProperty("testJavaVersion").orNull?.let { version ->
            javaLauncher.set(
                project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(version))
                },
            )
        }
    }
    dependencies {
        api(platform(project(dependenciesProject.path)))
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation("ch.qos.logback:logback-classic")
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testImplementation("me.ahoo.test:fluent-assert-core")
        testImplementation("io.mockk:mockk") {
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}

// 产物始终按 libs.versions.toml 的 Spring Boot 版本编译与发布；-PtestSpringBootVersion=4.0.8 只把测试运行时类路径
// 强制为该版本的 spring-boot-dependencies（Lettuce、Spring Data Redis、Jackson……一并降级），验证已发布的产物在较老的 Boot 上可用
providers.gradleProperty("testSpringBootVersion").orNull?.let { version ->
    configure(libraryProjects + serverProjects) {
        val testSpringBootPlatform = configurations.create("testSpringBootPlatform") {
            isCanBeResolved = false
            isCanBeConsumed = false
        }
        dependencies.add(testSpringBootPlatform.name, dependencies.enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$version"))
        // 测试运行时，以及 cocache-example 的测试 AOT 处理（processTestAot 在测试类路径上启动应用上下文，生成的源码再按同一组依赖编译）；
        // 主代码与测试代码的编译类路径保持不变
        val testRuntimeConfigurations = setOf("testRuntimeClasspath", "processTestAotClasspath", "aotTestCompileClasspath", "aotTestRuntimeClasspath")
        configurations.matching { it.name in testRuntimeConfigurations }.configureEach {
            extendsFrom(testSpringBootPlatform)
            // cocache-dependencies 引入的 4.1 平台也改指该版本：AOT 处理类路径复制依赖时会与强制版本冲突
            resolutionStrategy.eachDependency {
                if (requested.group == "org.springframework.boot" && requested.name == "spring-boot-dependencies") {
                    useVersion(version)
                }
            }
        }
    }
}

configure(publishProjects) {
    val isBom = bomProjects.contains(this)
    apply<MavenPublishPlugin>()
    apply<SigningPlugin>()
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "projectBuildRepo"
                url = uri(layout.buildDirectory.dir("repos"))
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Ahoo-Wang/CoCache")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            maven {
                name = "LinYiPackages"
                url = uri(providers.gradleProperty("linyiPackageReleaseUrl").orNull.toString())
                credentials {
                    username = providers.gradleProperty("linyiPackageUsername").orNull
                    password = providers.gradleProperty("linyiPackagePwd").orNull
                }
            }
        }
        publications {
            val publishName = if (isBom) "mavenBom" else "mavenLibrary"
            val publishComponentName = if (isBom) "javaPlatform" else "java"
            create<MavenPublication>(publishName) {
                from(components[publishComponentName])
                pom {
                    name.set(project.name)

                    description.set(provider { project.description ?: getPropertyOf("description") })
                    url.set(getPropertyOf("website"))
                    issueManagement {
                        system.set("GitHub")
                        url.set(getPropertyOf("issues"))
                    }
                    scm {
                        url.set(getPropertyOf("website"))
                        connection.set(getPropertyOf("vcs"))
                    }
                    licenses {
                        license {
                            name.set(getPropertyOf("license_name"))
                            url.set(getPropertyOf("license_url"))
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ahoo-wang")
                            name.set("ahoo wang")
                            organization {
                                url.set(getPropertyOf("website"))
                            }
                        }
                    }
                }
            }
        }
    }
    configure<SigningExtension> {
        val isInCI = null != System.getenv("CI")
        if (isInCI) {
            val signingKeyId = System.getenv("SIGNING_KEYID")
            val signingKey = System.getenv("SIGNING_SECRETKEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
        if (isBom) {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenBom"))
        } else {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenLibrary"))
        }
    }
}

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

val licenseHeader = "Licensed under the Apache License, Version 2.0"
val licensedSources = files(
    subprojects.map { sub -> fileTree(sub.projectDir) { include("src/**/*.kt", "src/**/*.java", "*.gradle.kts") } },
    "build.gradle.kts",
    "settings.gradle.kts",
)
val checkLicenseHeader = tasks.register("checkLicenseHeader") {
    group = "verification"
    description = "Verifies every Kotlin/Java source and build script carries the Apache-2.0 license header."
    val rootPath = rootDir.toPath()
    inputs.files(licensedSources)
    doLast {
        val missing = licensedSources.files.filterNot { it.readText().contains(licenseHeader) }
        check(missing.isEmpty()) {
            "Missing Apache-2.0 license header:\n" + missing.joinToString("\n") { rootPath.relativize(it.toPath()).toString() }
        }
    }
}
tasks.check {
    dependsOn(checkLicenseHeader)
}

fun getPropertyOf(name: String): String? = providers.gradleProperty(name).orNull
