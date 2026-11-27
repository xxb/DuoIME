# Keep JNI bridge class and native entrypoints bound by libduoime_rime.so
-keep class dev.duoime.engine.rime.JniRimeNativeBridge {
    native <methods>;
}
