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
    description = "Stream public domain movies, cartoons and TV series from Internet Archive"
    authors = listOf("DiscountProttoyHeron")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Documentary")
    language = "en"
    iconUrl = "https://archive.org/images/glogo.png"
}
