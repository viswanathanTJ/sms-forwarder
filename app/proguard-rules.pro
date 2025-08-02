# Keep Essential Components
-keep public class com.viswa2k.smsforwarder.MainActivity { *; }
-keep public class com.viswa2k.smsforwarder.SmsForwarderService { *; }
-keep public class com.viswa2k.smsforwarder.ServiceRestartReceiver { *; }
-keep public class com.viswa2k.smsforwarder.SMSReceiver { *; }

# Keep Android Components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep Permission Related Code
-keepclassmembers class * {
    @android.support.annotation.RequiresPermission <methods>;
}

# Keep Lifecycle Components
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Keep DataStore
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences$Key {
    public static <fields>;
}

# Keep Compose UI
-keep class androidx.compose.** { *; }

# Security Settings
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Keep source file names and line numbers
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
