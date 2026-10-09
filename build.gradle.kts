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
val detektReportMerge by tasks.registering(ReportMergeTask::class) {
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
        detektPlugins(dependenciesProject)
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
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict", "-Xjvm-default=all-compatibility")
            javaParameters = true
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
        api(platform(dependenciesProject))
        testImplementation(platform(rootProject.libs.junit.bom))
        implementation("org.slf4j:slf4j-api")
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
                url = uri(project.properties["linyiPackageReleaseUrl"].toString())
                credentials {
                    username = project.properties["linyiPackageUsername"]?.toString()
                    password = project.properties["linyiPackagePwd"]?.toString()
                }
            }
        }
        publications {
            val publishName = if (isBom) "mavenBom" else "mavenLibrary"
            val publishComponentName = if (isBom) "javaPlatform" else "java"
            create<MavenPublication>(publishName) {
                from(components[publishComponentName])
                pom {
                    name.set(rootProject.name)

                    description.set(getPropertyOf("description"))
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
val checkLicenseHeader by tasks.registering {
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

fun getPropertyOf(name: String) = project.properties[name]?.toString()
