# Keep WebRTC native and JNI bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep Gson models
-keepclassmembers class com.livedevicestream.signaling.** { <fields>; }
