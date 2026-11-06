-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.sigil.app.** { *; }

# Remove debug logging from release builds
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
