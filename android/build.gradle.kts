// Root build file — plugin versions only, resolved but not applied here (docs/design-principles.md
// "easy to change": every module applies just the plugins it needs).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
