extra["extName"] = "CinemaOS"
extra["pkgName"] = "com.cinemaos.tv"
extra["extVersionCode"] = 1

apply(from = "$rootDir/build.gradle.kts")

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

