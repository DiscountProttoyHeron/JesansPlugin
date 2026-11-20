import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = 3

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

cloudstream {
    description = "Stream public-domain classic films and documentaries from Internet Archive with polymorphic JSON shield and HTML self-healing fallback."
    authors = listOf("DiscountProttoyHeron")
    status = 1
    tvTypes = listOf("Movie", "Documentary")
    language = "en"
    iconUrl = "https://archive.org/images/glogo.png"
}
