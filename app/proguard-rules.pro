# Proguard / R8 rules for DisasterManagement
-keep class network.disaster.management.crypto.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Preserve all native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve domain data models
-keep class network.disaster.management.model.** { *; }

# Preserve ZXing barcode components
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Preserve CameraX components
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

