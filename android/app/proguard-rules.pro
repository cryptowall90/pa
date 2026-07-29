# GPUImage uses native/JNI and reflection-friendly filter classes.
-keep class jp.co.cyberagent.android.gpuimage.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Room generated code
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**
