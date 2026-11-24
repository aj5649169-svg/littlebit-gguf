# JNI resolves these names/descriptors at runtime via FindClass/GetMethodID.
-keep class com.littlebit.chat.engine.LlamaJni { *; }
-keep interface com.littlebit.chat.engine.LlamaEngine$ProgressCallback { *; }
-keep interface com.littlebit.chat.engine.LlamaEngine$TokenCallback { *; }
-keepclassmembers class * implements com.littlebit.chat.engine.LlamaEngine$ProgressCallback { public *; }
-keepclassmembers class * implements com.littlebit.chat.engine.LlamaEngine$TokenCallback { public *; }
# History uses explicit org.json keys; its schema is independent of JVM names.
-keepnames class com.littlebit.chat.data.ModelEntry
-keepnames class com.littlebit.chat.data.ChatMessage
-keepnames class com.littlebit.chat.data.ChatSession
