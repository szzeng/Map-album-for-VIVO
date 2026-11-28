# Baidu's published map and location SDK rules protect JNI and reflection.
-keep class com.baidu.** { *; }
-keep class vi.com.** { *; }
-keep class com.baidu.vi.** { *; }
-dontwarn com.baidu.**
