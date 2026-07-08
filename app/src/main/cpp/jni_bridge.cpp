#include <jni.h>

#include <vector>

#include "AudioEngine.h"

namespace {
AudioEngine gEngine;

std::vector<float> toVector(JNIEnv* env, jfloatArray array) {
    std::vector<float> v(static_cast<size_t>(env->GetArrayLength(array)));
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeStart(
        JNIEnv* /*env*/, jobject /*thiz*/, jint inputDeviceId, jint outputDeviceId) {
    return gEngine.start(inputDeviceId, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.stop();
}

JNIEXPORT jboolean JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeIsRunning(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeIsDisconnected(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.isDisconnected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSendCommand(
        JNIEnv* /*env*/, jobject /*thiz*/, jint command) {
    gEngine.looper().sendCommand(static_cast<LooperCommand>(command));
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetState(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(gEngine.looper().state());
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetPositionFrames(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.looper().positionFrames();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetLoopLengthFrames(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.looper().loopLengthFrames();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetMaxLoopFrames(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.looper().maxLoopFrames();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetSampleRate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetFramesPerBurst(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.framesPerBurst();
}

JNIEXPORT jboolean JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeLoadLoop(
        JNIEnv* env, jobject /*thiz*/, jfloatArray samples) {
    const jsize length = env->GetArrayLength(samples);
    jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
    if (ptr == nullptr) {
        return JNI_FALSE;
    }
    const bool ok = gEngine.loadLoop(ptr, static_cast<int32_t>(length));
    env->ReleaseFloatArrayElements(samples, ptr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeCopyLoop(
        JNIEnv* env, jobject /*thiz*/, jfloatArray dest) {
    const jsize capacity = env->GetArrayLength(dest);
    jfloat* ptr = env->GetFloatArrayElements(dest, nullptr);
    if (ptr == nullptr) {
        return 0;
    }
    const int32_t copied = gEngine.copyLoop(ptr, static_cast<int32_t>(capacity));
    env->ReleaseFloatArrayElements(dest, ptr, 0);
    return copied;
}

JNIEXPORT jfloat JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeReadInputPeak(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.readInputPeak();
}

JNIEXPORT jfloat JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeReadOutputPeak(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.readOutputPeak();
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetMonitor(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    gEngine.setMonitorEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetInputGain(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain) {
    gEngine.setInputGain(gain);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetOutputGain(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain) {
    gEngine.setOutputGain(gain);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetBpm(
        JNIEnv* /*env*/, jobject /*thiz*/, jint bpm) {
    gEngine.looper().rhythm().setBpm(bpm);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetMetronome(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    gEngine.looper().rhythm().setMetronomeEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetDrums(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    gEngine.looper().rhythm().setDrumsEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetTimeSignature(
        JNIEnv* /*env*/, jobject /*thiz*/, jint index) {
    gEngine.looper().rhythm().setTimeSignature(index);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetRhythmVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat volume) {
    gEngine.looper().rhythm().setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetCountIn(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    gEngine.looper().setCountInEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetLoopVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain) {
    gEngine.looper().setLoopGain(gain);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetAutoLoopBars(
        JNIEnv* /*env*/, jobject /*thiz*/, jint bars) {
    gEngine.looper().setAutoLoopBars(bars);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeSetDrumSamples(
        JNIEnv* env, jobject /*thiz*/, jfloatArray kick, jfloatArray snare,
        jfloatArray hat, jint sourceRate) {
    gEngine.setDrumSamples(toVector(env, kick), toVector(env, snare),
                           toVector(env, hat), sourceRate);
}

// Returns null on success, otherwise a readable error message.
JNIEXPORT jstring JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamLoadModel(
        JNIEnv* env, jobject /*thiz*/, jint slot, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("Out of memory");
    }
    const std::string result = gEngine.namLoadModel(slot, chars);
    env->ReleaseStringUTFChars(path, chars);
    return result.empty() ? nullptr : env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamClearSlot(
        JNIEnv* /*env*/, jobject /*thiz*/, jint slot) {
    gEngine.namClearSlot(slot);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamSetActiveSlot(
        JNIEnv* /*env*/, jobject /*thiz*/, jint slot) {
    gEngine.namSetActiveSlot(slot);
}

JNIEXPORT jdouble JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamExpectedSampleRate(
        JNIEnv* /*env*/, jobject /*thiz*/, jint slot) {
    return gEngine.namExpectedSampleRate(slot);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamMaintenance(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.namMaintenance();
}

JNIEXPORT jfloat JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeReadFxPeak(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.readFxPeak();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeGetXRunCount(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.xRunCount();
}

JNIEXPORT jfloat JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeReadDspLoad(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.readDspLoad();
}

// --- IR loader (Slot D, fixed/global cab IR) -------------------------
JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeIrLoad(
        JNIEnv* env, jobject /*thiz*/, jfloatArray coeffs) {
    const jsize length = env->GetArrayLength(coeffs);
    jfloat* ptr = (length > 0) ? env->GetFloatArrayElements(coeffs, nullptr) : nullptr;
    if (length > 0 && ptr == nullptr) {
        return;
    }
    gEngine.irLoad(ptr, static_cast<int32_t>(length));
    if (ptr != nullptr) {
        env->ReleaseFloatArrayElements(coeffs, ptr, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeIrClear(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.irClear();
}

// --- Compare mode: bypass + live capture -----------------------------
JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeNamBypass(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean bypass) {
    gEngine.setNamBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeIrBypass(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean bypass) {
    gEngine.setIrBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeCaptureStart(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.startCapture();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeCaptureStop(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.stopCapture();
}

JNIEXPORT jint JNICALL
Java_com_example_dnichelooper_audio_AudioEngine_nativeCopyCapture(
        JNIEnv* env, jobject /*thiz*/, jfloatArray dest) {
    const jsize capacity = env->GetArrayLength(dest);
    jfloat* ptr = env->GetFloatArrayElements(dest, nullptr);
    if (ptr == nullptr) return 0;
    const int32_t copied = gEngine.copyCapture(ptr, static_cast<int32_t>(capacity));
    env->ReleaseFloatArrayElements(dest, ptr, 0);
    return copied;
}

}  // extern "C"
