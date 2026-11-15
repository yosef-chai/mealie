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

# סורק ה-QR (play-services-code-scanner 16.1.0) מושך את firebase-components 16.1.0, שאין בו כלל keep
# לבנאים של ה-ComponentRegistrar ש-ML Kit טוען ברפלקציה. ב-R8 full mode (ברירת המחדל מ-AGP 9)
# הבנאים נמחקים ו-GmsBarcodeScanning.getClient קורס ב-NullPointerException.
# זה הכלל ש-firebase-components הוסיף בגרסאות מאוחרות יותר. https://github.com/googlesamples/mlkit/issues/1018
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
-dontwarn com.google.firebase.components.Component$Instantiation
-dontwarn com.google.firebase.components.Component$ComponentType
