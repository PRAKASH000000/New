extra["extName"] = "CinemaOS"
extra["pkgName"] = "com.cinemaos.tv"
extra["extVersionCode"] = 1

apply(from = "$rootDir/build.gradle.kts")

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}
