# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yaz.sms.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaz.sms.** {
    kotlinx.serialization.KSerializer serializer(...);
}
