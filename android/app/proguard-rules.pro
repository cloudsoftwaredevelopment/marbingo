# Moshi reflection + Retrofit interfaces (DTOs live in ph.marbingo.core.model)
-keep class ph.marbingo.core.model.** { *; }
-keep interface ph.marbingo.core.net.MarbingoApi { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**

# Moshi's KotlinJsonAdapterFactory reads Kotlin metadata reflectively
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
# Receipt data and Room entities are mapped by field name
-keep class ph.marbingo.core.print.** { *; }
-keep class ph.marbingo.field.data.db.** { *; }
# Retrofit suspend functions
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
