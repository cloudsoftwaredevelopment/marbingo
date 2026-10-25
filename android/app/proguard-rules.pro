# Moshi reflection + Retrofit interfaces (DTOs live in ph.marbingo.core.model)
-keep class ph.marbingo.core.model.** { *; }
-keep interface ph.marbingo.core.net.MarbingoApi { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
