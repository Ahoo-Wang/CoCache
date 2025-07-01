dependencies {
    api(project(":cocache-api"))
    api("me.ahoo.cosid:cosid-core")
    api(kotlin("reflect"))
    compileOnly("com.google.guava:guava")
    compileOnly("com.github.ben-manes.caffeine:caffeine")
    api("io.github.oshai:kotlin-logging-jvm")
    implementation("org.springframework:spring-expression")
    testImplementation("com.google.guava:guava")
    testImplementation("com.github.ben-manes.caffeine:caffeine")
    testImplementation(project(":cocache-test"))
}
