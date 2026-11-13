# Keep all application classes and members intact while tree-shaking unused library code
-dontobfuscate
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

-keep class com.example.** { *; }
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }
-keep class * extends android.view.View { *; }
