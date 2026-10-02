# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.sms.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.sms.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
