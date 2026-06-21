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

# kotlinx.serialization — keep generated serializers for our @Serializable models
# (CloudSmsPayload, CloudMessageRepository.FanOut/RecipientCopy used for the offline queue)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.viswa2k.smsforwarder.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.viswa2k.smsforwarder.**$$serializer { *; }

# Google Tink (HPKE/AEAD) registers primitives reflectively
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
