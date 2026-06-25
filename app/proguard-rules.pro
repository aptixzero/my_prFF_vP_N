# Keep the JNI bridge class name EXACTLY (native lib registers against it)
-keep class com.v2ray.ang.service.TProxyService { *; }

# Keep libv2ray / gomobile classes (reflection + JNI)
-keep class libv2ray.** { *; }
-keep class go.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn libv2ray.**
-dontwarn go.**
