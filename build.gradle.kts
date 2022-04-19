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

plugins {
    id("io.github.gradle-nexus.publish-plugin")
    java
    jacoco
}

val bomProjects = setOf(
    project(":cocache-bom"),
    project(":cocache-dependencies")
)

val coreProjects = setOf(
    project(":cocache-core")
)

val serverProjects = setOf(
    project(":cocache-example")
)

val publishProjects = subprojects - serverProjects
val libraryProjects = publishProjects - bomProjects

ext {
    set("lombokVersion", "1.18.20")
    set("guavaVersion", "30.0-jre")
    set("springBootVersion", "2.6.4")
    set("springCloudVersion", "2021.0.1")
    set("springfoxVersion", "3.0.0")
    set("jmhVersion", "1.34")
    set("cosIdVersion", "1.8.11")
    set("junitPioneerVersion", "1.4.2")
    set("libraryProjects", libraryProjects)
}


allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

configure(bomProjects) {
    apply<JavaPlatformPlugin>()
    configure<JavaPlatformExtension> {
        allowDependencies()
    }
}


configure(libraryProjects) {
    apply<CheckstylePlugin>()
    configure<CheckstyleExtension> {
        toolVersion = "9.2.1"
    }
    apply<com.github.spotbugs.snom.SpotBugsPlugin>()
    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        excludeFilter.set(file("${rootDir}/config/spotbugs/exclude.xml"))
    }
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
        withJavadocJar()
        withSourcesJar()
    }
    apply<me.champeau.jmh.JMHPlugin>()
    configure<me.champeau.jmh.JmhParameters> {
        val DELIMITER = ',';
        val JMH_INCLUDES_KEY = "jmhIncludes"
        val JMH_EXCLUDES_KEY = "jmhExcludes"
        val JMH_THREADS_KEY = "jmhThreads"
        val JMH_MODE_KEY = "jmhMode"

        if (project.hasProperty(JMH_INCLUDES_KEY)) {
            val jmhIncludes = project.properties[JMH_INCLUDES_KEY].toString().split(DELIMITER)
            includes.set(jmhIncludes)
        }
        if (project.hasProperty(JMH_EXCLUDES_KEY)) {
            val jmhExcludes = project.properties[JMH_EXCLUDES_KEY].toString().split(DELIMITER)
            excludes.set(jmhExcludes)
        }

        jmhVersion.set(rootProject.ext.get("jmhVersion").toString())
        warmupIterations.set(1)
        iterations.set(1)
        resultFormat.set("json")

        var jmhMode = listOf(
            "thrpt"
        )
        if (project.hasProperty(JMH_MODE_KEY)) {
            jmhMode = project.properties[JMH_MODE_KEY].toString().split(DELIMITER)
        }
        benchmarkMode.set(jmhMode)
        var jmhThreads = 1
        if (project.hasProperty(JMH_THREADS_KEY)) {
            jmhThreads = Integer.valueOf(project.properties[JMH_THREADS_KEY].toString())
        }
        threads.set(jmhThreads)
        fork.set(1)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }


    dependencies {
        val depLombok = "org.projectlombok:lombok:${rootProject.ext.get("lombokVersion")}"
        this.add("api", platform(project(":cocache-dependencies")))
        this.add("compileOnly", depLombok)
        this.add("annotationProcessor", depLombok)
        this.add("testCompileOnly", depLombok)
        this.add("testAnnotationProcessor", depLombok)
        this.add("implementation", "com.google.guava:guava")
        this.add("implementation", "org.slf4j:slf4j-api")
        this.add("testImplementation", "ch.qos.logback:logback-classic")
        this.add("testImplementation", "org.junit.jupiter:junit-jupiter-api")
        this.add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine")
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
                    username = project.findProperty("gitHubPackagesUserName") as? String
                    password = project.findProperty("gitHubPackagesToken") as? String?
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
        if (isBom) {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenBom"))
        } else {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenLibrary"))
        }
    }
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

fun getPropertyOf(name: String) = project.properties[name]?.toString()

tasks.register<JacocoReport>("codeCoverageReport") {
    executionData(fileTree(project.rootDir.absolutePath).include("**/build/jacoco/*.exec"))
    libraryProjects.forEach {
        sourceSets(it.sourceSets.main.get())
    }
    reports {
        xml.required.set(true)
        html.outputLocation.set(file("${buildDir}/reports/jacoco/report.xml"))
        csv.required.set(false)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/"))
    }
}
