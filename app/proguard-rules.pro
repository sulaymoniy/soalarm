# Only used if you turn on isMinifyEnabled in app/build.gradle.kts.
# MediaPipe uses native code and protobuf reflection, so keep its classes.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
