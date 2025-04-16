plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}
java {
    registerFeature("actuatorSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "actuator-support", version.toString())
    }
    registerFeature("cloudSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "cloud-support", version.toString())
    }
}
dependencies {
    kapt(platform(project(":cocache-dependencies")))
    api(project(":cocache-spring"))
    api(project(":cocache-spring-cache"))
    api(project(":cocache-spring-redis"))
    api("org.springframework.boot:spring-boot-starter")
    "actuatorSupportImplementation"("org.springframework.boot:spring-boot-starter-actuator")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation("com.google.guava:guava")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":cocache-example"))
}

