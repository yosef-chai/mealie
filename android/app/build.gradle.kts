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

// לכל משאב: המפתח ב-Mealie, וטקסט באנגלית למקרה שהמפתח ישתנה או יימחק במקור (הבנייה ממשיכה עם אזהרה)
val mealieStrings = listOf(
    Triple("mealie_recipes", "general.recipes", "Recipes"),
    Triple("mealie_meal_planner", "meal-plan.meal-planner", "Meal Planner"),
    Triple("mealie_shopping_lists", "shopping-list.shopping-lists", "Shopping Lists"),
    Triple("mealie_cookbooks", "sidebar.cookbooks", "Cookbooks"),
    Triple("mealie_settings", "general.settings", "Settings"),
    Triple("mealie_cancel", "general.cancel", "Cancel"),
    Triple("mealie_close", "general.close", "Close"),
    Triple("mealie_save", "general.save", "Save"),
    Triple("mealie_delete", "general.delete", "Delete"),
    Triple("mealie_update", "general.update", "Update"),
    Triple("mealie_back", "general.back", "Back"),
    Triple("mealie_loading", "general.loading", "Loading"),
    Triple("mealie_login", "user.login", "Login"),
    Triple("mealie_language", "sidebar.language", "Language"),
    Triple("mealie_api_token", "settings.token.api-token", "API Token"),
    Triple("mealie_breakfast", "meal-plan.breakfast", "Breakfast"),
    Triple("mealie_lunch", "meal-plan.lunch", "Lunch"),
    Triple("mealie_dinner", "meal-plan.dinner", "Dinner"),
    Triple("mealie_side", "meal-plan.side", "Side"),
    Triple("mealie_snack", "meal-plan.snack", "Snack"),
    Triple("mealie_drink", "meal-plan.drink", "Drink"),
    Triple("mealie_dessert", "meal-plan.dessert", "Dessert"),
    Triple("mealie_edit", "general.edit", "Edit"),
    Triple("mealie_recipe_created", "recipe.recipe-created", "Recipe created"),
)

val generateMealieStrings = tasks.register<GenerateStringsTask>("generateMealieStrings") {
    mealieLangDir.set(langDirProvider)
    appI18nDir.set(rootProject.layout.projectDirectory.dir("i18n"))
    outputDir.set(layout.buildDirectory.dir("generated/res/mealieStrings"))
    mealieKeys.set(mealieStrings.associate { (res, key, _) -> res to key })
    mealieFallbacks.set(mealieStrings.associate { (res, _, english) -> res to english })
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
            // עדכון עצמי רק בבנייה שחתומה במפתח השחרור; אחרת ההתקנה נכשלת על חתימה שונה
            buildConfigField("boolean", "SELF_UPDATE", releaseKeystore.isPresent.toString())
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
            // מזהה חבילה וחתימה שונים מגרסת השחרור, ולכן אין למשוך ממנה עדכונים
            buildConfigField("boolean", "SELF_UPDATE", "false")
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.code.scanner)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
