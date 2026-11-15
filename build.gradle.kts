plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 intègre Kotlin : le plugin kotlin.android n'est plus appliqué, il sert seulement à fixer la version de Kotlin
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
