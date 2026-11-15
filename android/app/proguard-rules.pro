# מודלי JSON (kotlinx.serialization) נשמרים עם השמות שלהם.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class io.github.yosefchai.mealie.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.yosefchai.mealie.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp: מחלקות אופציונליות של פלטפורמות אחרות.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
