description = "Derived metadata search"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation("org.springframework:spring-jdbc")
    testImplementation(kotlin("test"))
}

val integrationTestSourceSet = sourceSets.create("integrationTest")
integrationTestSourceSet.compileClasspath += sourceSets.main.get().output
integrationTestSourceSet.runtimeClasspath += sourceSets.main.get().output
configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(integrationTestSourceSet.implementationConfigurationName, "org.postgresql:postgresql:42.7.5")
    add(integrationTestSourceSet.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql:2.0.5")
}
tasks.register<Test>("integrationTest") {
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
