# kotlinx.serialization keeps generated serializers via consumer rules.
# Repeat the essentials here so full-mode R8 never drops backup/domain serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.deepsleep.chat.**$$serializer { *; }
-keepclassmembers class com.deepsleep.chat.** { *** Companion; }
-keepclasseswithmembers class com.deepsleep.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Room and OkHttp ship their own consumer rules.

# Markwon uses reflection only for optional image loaders; none are used here.
-dontwarn org.commonmark.**