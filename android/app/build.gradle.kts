plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// גרסת Mealie: ה-CI מעביר -PmealieVersion; מקומית נקראת מ-pyproject.toml של המאגר.
val mealieVersion = providers.gradleProperty("mealieVersion").orElse(
    providers.fileContents(rootProject.layout.projectDirectory.file("../pyproject.toml")).asText.map { text ->
        Regex("""(?m)^version\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: error("No version in pyproject.toml")
    },
).get()
val appPatch = providers.gradleProperty("appPatch").get().toInt()
val updateRepo = providers.gradleProperty("updateRepo").orElse("yosef-chai/mealie").get()

// ה-CI מעביר את תיקיית התרגומים של תגית ה-Mealie ששוחררה; מקומית: התרגומים במאגר עצמו.
val langDirProvider = providers.gradleProperty("mealieLangDir")
    .map { rootProject.layout.projectDirectory.dir(it) }
    .orElse(rootProject.layout.projectDirectory.dir("../frontend/app/lang/messages"))

val generateMealieStrings = tasks.register<GenerateStringsTask>("generateMealieStrings") {
    mealieLangDir.set(langDirProvider)
    appI18nDir.set(rootProject.layout.projectDirectory.dir("i18n"))
    outputDir.set(layout.buildDirectory.dir("generated/res/mealieStrings"))
    mealieKeys.set(
        mapOf(
            "mealie_recipes" to "general.recipes",
            "mealie_meal_planner" to "meal-plan.meal-planner",
            "mealie_shopping_lists" to "shopping-list.shopping-lists",
            "mealie_cookbooks" to "sidebar.cookbooks",
            "mealie_settings" to "general.settings",
            "mealie_cancel" to "general.cancel",
            "mealie_close" to "general.close",
            "mealie_save" to "general.save",
            "mealie_confirm" to "general.confirm",
            "mealie_delete" to "general.delete",
            "mealie_update" to "general.update",
            "mealie_share" to "general.share",
            "mealie_back" to "general.back",
            "mealie_download" to "general.download",
            "mealie_import" to "general.import",
            "mealie_loading" to "general.loading",
            "mealie_version" to "about.version",
            "mealie_login" to "user.login",
            "mealie_logout" to "user.logout",
            "mealie_language" to "sidebar.language",
            "mealie_api_token" to "settings.token.api-token",
        ),
    )
}

val releaseKeystore = providers.environmentVariable("MEALIE_KEYSTORE_FILE")
val requireSigning = providers.environmentVariable("MEALIE_REQUIRE_SIGNING").map { it == "true" }.orElse(false)

android {
    namespace = "io.github.yosefchai.mealie"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yosefchai.mealie"
        minSdk = 26
        targetSdk = 37
        versionCode = MealieVersion.versionCode(mealieVersion, appPatch)
        versionName = MealieVersion.versionName(mealieVersion, appPatch)

        buildConfigField("String", "MEALIE_VERSION", "\"$mealieVersion\"")
        buildConfigField("int", "APP_PATCH", "$appPatch")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        manifestPlaceholders["oauthScheme"] = "io.github.yosefchai.mealie"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("MEALIE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MEALIE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MEALIE_KEY_PASSWORD").get()
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                releaseKeystore.isPresent -> signingConfigs.getByName("release")
                requireSigning.get() -> error("MEALIE_REQUIRE_SIGNING=true but MEALIE_KEYSTORE_FILE is not set")
                // בנייה מקומית בלי מפתח: חתימת debug, לא להפצה.
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateMealieStrings, GenerateStringsTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navsuite)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
