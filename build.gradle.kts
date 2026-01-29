// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}

// Repositories are now managed in settings.gradle.kts via dependencyResolutionManagement
// Do NOT add buildscript.repositories or allprojects.repositories here

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
