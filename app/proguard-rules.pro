# 供应商 SDK 若引入反射型模型，保留其序列化类
-keep class com.parley.speaker.data.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.conscrypt.**
