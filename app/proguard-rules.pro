# Media3 and Room ship their own consumer rules. Nothing extra needed yet.

## NewPipeExtractor (online flavor). Rhino is used to decode YouTube's stream signatures.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# NewPipeExtractor 0.27+ builds some YouTube requests with protobuf-lite, which reads message fields by name.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn com.google.protobuf.**
