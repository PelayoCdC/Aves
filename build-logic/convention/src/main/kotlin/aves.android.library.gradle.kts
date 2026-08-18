import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

// En precompiled script plugins no existe el accesor `libs`, hay que pedir el
// catalogo a mano.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<LibraryExtension> {
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Sin `kotlinOptions {}`: AGP 9 usa Kotlin integrado y esa DSL esta obsoleta.
    // jvmTarget hereda automaticamente de compileOptions.targetCompatibility.
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
