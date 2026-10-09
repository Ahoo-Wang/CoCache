dependencies {
    api(project(":cocache-api"))
    api("me.ahoo.cosid:cosid-core")
    api(kotlin("reflect"))
    api("com.github.ben-manes.caffeine:caffeine")
    compileOnly("com.google.guava:guava")
    api("io.github.oshai:kotlin-logging-jvm")
    implementation("org.springframework:spring-expression")
    testImplementation("com.google.guava:guava")
    testImplementation(project(":cocache-test"))
}
