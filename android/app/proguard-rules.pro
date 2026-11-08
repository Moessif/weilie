# 暂不混淆；后续启用时保留序列化模型与 ktor
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gakj.weilie.** {
    *** Companion;
}
-keepclasseswithmembers class com.gakj.weilie.** {
    kotlinx.serialization.KSerializer serializer(...);
}
