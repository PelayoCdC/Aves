import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // 17 explicito: estos modulos los consume codigo Android, asi que el
        // bytecode no puede ser mas nuevo de lo que D8 acepta.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
