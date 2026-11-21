import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = 1

extensions.configure<BaseExtension>("android") {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

cloudstream {
    description = "Stream public-domain classic films and documentaries from Internet Archive with polymorphic JSON shield and HTML self-healing fallback."
    authors = listOf("HaxerAhmed")
    status = 1
    tvTypes = listOf("Movie", "Documentary")
    language = "en"
    iconUrl = "https://archive.org/images/glogo.png"
}
