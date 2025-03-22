dependencies {
    api(project(":cocache-api"))
    api("me.ahoo.cosid:cosid-core")
    implementation(kotlin("reflect"))
    implementation("com.google.guava:guava")
    api("io.github.oshai:kotlin-logging-jvm")
    implementation("org.springframework:spring-expression")
    testImplementation(project(":cocache-test"))
}
