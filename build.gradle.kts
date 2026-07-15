// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// Register the "pre" build variant in all submodules so the :app module can
// resolve its preRuntimeClasspath dependencies. The "pre" variant is identical
// to "release" — minified, shrunk, and proguarded.
subprojects {
    plugins.whenPluginAdded {
        when {
            this@whenPluginAdded is com.android.build.gradle.LibraryPlugin -> {
                extensions.configure<com.android.build.gradle.LibraryExtension> {
                    buildTypes { create("pre") { initWith(getByName("release")) } }
                }
            }
            this@whenPluginAdded is com.android.build.gradle.TestPlugin -> {
                extensions.configure<com.android.build.gradle.TestExtension> {
                    buildTypes { create("pre") { initWith(getByName("release")) } }
                }
            }
        }
    }
}
