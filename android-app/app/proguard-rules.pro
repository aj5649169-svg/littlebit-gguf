# ProGuard is disabled for debug builds of the engine JNI bridge.
# Keep JNI-called classes/methods (external fun declarations are kept by R8
# via the native signature, but be explicit for safety).
-keepclassmembers class com.littlebit.chat.engine.LlamaJni {
    native <methods>;
}
-keep class com.littlebit.chat.engine.LlamaEngine$* { *; }
