plugins {
    id("rikkahub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.highlight"

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    api(libs.quickjs)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
