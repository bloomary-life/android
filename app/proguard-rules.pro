# Keep generic signatures and annotations needed by reflection-based libraries.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# ---------------------------------------------------------------------------
# kotlinx.serialization
# https://github.com/Kotlin/kotlinx.serialization#android
# ---------------------------------------------------------------------------
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}

# Keep `Companion` object fields of serializable classes so the plugin can
# locate `serializer()`.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both static and synthetic) of
# serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
    kotlinx.serialization.KSerializer[] childSerializers();
    static <1>$<3>[] values();
}

# Keep serializable objects (INSTANCE holder).
-if @kotlinx.serialization.Serializable class ** {
    static ** INSTANCE;
}
-keepclassmembers class <1> {
    static <1> INSTANCE;
}

# SerialName annotations on fields must survive for enum/value class handling.
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Sentry
# ---------------------------------------------------------------------------
-dontwarn io.sentry.**
-keepclassmembers class * {
    @io.sentry.protocol.SentryThread <fields>;
}

# ---------------------------------------------------------------------------
# Tink
# ---------------------------------------------------------------------------
-dontwarn com.google.crypto.tink.**
