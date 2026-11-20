import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = 4

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
    description = "Stream dubbed anime and cartoon movies & series from WatchAnimeWorld"
    authors = listOf("DiscountProttoyHeron")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "hin"
    iconUrl = "https://watchanimeworld.one/favicon.ico"
}
