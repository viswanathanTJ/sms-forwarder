# Keep application class
-keep public class com.viswa2k.** { *; }

# Keep any other classes you need
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
# Add any other libraries you are using

# Remove unused code
-dontshrink
-dontoptimize
