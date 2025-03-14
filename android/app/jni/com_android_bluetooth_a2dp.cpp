/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BluetoothA2dpServiceJni"

#include "com_android_bluetooth.h"
#include "hardware/bt_av.h"
#include "utils/Log.h"

#include <string.h>
#include <shared_mutex>

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onAudioStateChanged;
static jmethodID method_onCodecConfigChanged;
static jmethodID method_isMandatoryCodecPreferred;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID getCodecType;
  jmethodID getCodecPriority;
  jmethodID getSampleRate;
  jmethodID getBitsPerSample;
  jmethodID getChannelMode;
  jmethodID getCodecSpecific1;
  jmethodID getCodecSpecific2;
  jmethodID getCodecSpecific3;
  jmethodID getCodecSpecific4;
} android_bluetooth_BluetoothCodecConfig;

static const btav_source_interface_t* sBluetoothA2dpInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static void bta2dp_connection_state_callback(const RawAddress& bd_addr,
                                             btav_connection_state_t state) {
  ALOGI("%s", __func__);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("%s: Fail to new jbyteArray bd addr", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(
      addr.get(), 0, sizeof(RawAddress),
      reinterpret_cast<const jbyte*>(bd_addr.address));
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged,
                               addr.get(), (jint)state);
}

static void bta2dp_audio_state_callback(const RawAddress& bd_addr,
                                        btav_audio_state_t state) {
  ALOGI("%s", __func__);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("%s: Fail to new jbyteArray bd addr", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(
      addr.get(), 0, sizeof(RawAddress),
      reinterpret_cast<const jbyte*>(bd_addr.address));
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioStateChanged,
                               addr.get(), (jint)state);
}

static void bta2dp_audio_config_callback(
    const RawAddress& bd_addr, btav_a2dp_codec_config_t codec_config,
    std::vector<btav_a2dp_codec_config_t> codecs_local_capabilities,
    std::vector<btav_a2dp_codec_config_t> codecs_selectable_capabilities) {
  ALOGI("%s", __func__);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

  jobject codecConfigObj = sCallbackEnv->NewObject(
      android_bluetooth_BluetoothCodecConfig.clazz,
      android_bluetooth_BluetoothCodecConfig.constructor,
      (jint)codec_config.codec_type, (jint)codec_config.codec_priority,
      (jint)codec_config.sample_rate, (jint)codec_config.bits_per_sample,
      (jint)codec_config.channel_mode, (jlong)codec_config.codec_specific_1,
      (jlong)codec_config.codec_specific_2,
      (jlong)codec_config.codec_specific_3,
      (jlong)codec_config.codec_specific_4);

  jsize i = 0;
  jobjectArray local_capabilities_array = sCallbackEnv->NewObjectArray(
      (jsize)codecs_local_capabilities.size(),
      android_bluetooth_BluetoothCodecConfig.clazz, nullptr);
  for (auto const& cap : codecs_local_capabilities) {
    jobject capObj = sCallbackEnv->NewObject(
        android_bluetooth_BluetoothCodecConfig.clazz,
        android_bluetooth_BluetoothCodecConfig.constructor,
        (jint)cap.codec_type, (jint)cap.codec_priority, (jint)cap.sample_rate,
        (jint)cap.bits_per_sample, (jint)cap.channel_mode,
        (jlong)cap.codec_specific_1, (jlong)cap.codec_specific_2,
        (jlong)cap.codec_specific_3, (jlong)cap.codec_specific_4);
    sCallbackEnv->SetObjectArrayElement(local_capabilities_array, i++, capObj);
    sCallbackEnv->DeleteLocalRef(capObj);
  }

  i = 0;
  jobjectArray selectable_capabilities_array = sCallbackEnv->NewObjectArray(
      (jsize)codecs_selectable_capabilities.size(),
      android_bluetooth_BluetoothCodecConfig.clazz, nullptr);
  for (auto const& cap : codecs_selectable_capabilities) {
    jobject capObj = sCallbackEnv->NewObject(
        android_bluetooth_BluetoothCodecConfig.clazz,
        android_bluetooth_BluetoothCodecConfig.constructor,
        (jint)cap.codec_type, (jint)cap.codec_priority, (jint)cap.sample_rate,
        (jint)cap.bits_per_sample, (jint)cap.channel_mode,
        (jlong)cap.codec_specific_1, (jlong)cap.codec_specific_2,
        (jlong)cap.codec_specific_3, (jlong)cap.codec_specific_4);
    sCallbackEnv->SetObjectArrayElement(selectable_capabilities_array, i++,
                                        capObj);
    sCallbackEnv->DeleteLocalRef(capObj);
  }

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(RawAddress::kLength));
  if (!addr.get()) {
    ALOGE("%s: Fail to new jbyteArray bd addr", __func__);
    return;
  }
  sCallbackEnv->SetByteArrayRegion(
      addr.get(), 0, RawAddress::kLength,
      reinterpret_cast<const jbyte*>(bd_addr.address));

  sCallbackEnv->CallVoidMethod(
      mCallbacksObj, method_onCodecConfigChanged, addr.get(), codecConfigObj,
      local_capabilities_array, selectable_capabilities_array);
}

static bool bta2dp_mandatory_codec_preferred_callback(
    const RawAddress& bd_addr) {
  ALOGI("%s", __func__);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return false;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(RawAddress::kLength));
  if (!addr.get()) {
    ALOGE("%s: Fail to new jbyteArray bd addr", __func__);
    return false;
  }
  sCallbackEnv->SetByteArrayRegion(
      addr.get(), 0, RawAddress::kLength,
      reinterpret_cast<const jbyte*>(bd_addr.address));
  return sCallbackEnv->CallBooleanMethod(
      mCallbacksObj, method_isMandatoryCodecPreferred, addr.get());
}

static btav_source_callbacks_t sBluetoothA2dpCallbacks = {
    sizeof(sBluetoothA2dpCallbacks),
    bta2dp_connection_state_callback,
    bta2dp_audio_state_callback,
    bta2dp_audio_config_callback,
    bta2dp_mandatory_codec_preferred_callback,
};

static void classInitNative(JNIEnv* env, jclass clazz) {
  jclass jniBluetoothCodecConfigClass =
      env->FindClass("android/bluetooth/BluetoothCodecConfig");
  android_bluetooth_BluetoothCodecConfig.constructor =
      env->GetMethodID(jniBluetoothCodecConfigClass, "<init>", "(IIIIIJJJJ)V");
  android_bluetooth_BluetoothCodecConfig.getCodecType =
      env->GetMethodID(jniBluetoothCodecConfigClass, "getCodecType", "()I");
  android_bluetooth_BluetoothCodecConfig.getCodecPriority =
      env->GetMethodID(jniBluetoothCodecConfigClass, "getCodecPriority", "()I");
  android_bluetooth_BluetoothCodecConfig.getSampleRate =
      env->GetMethodID(jniBluetoothCodecConfigClass, "getSampleRate", "()I");
  android_bluetooth_BluetoothCodecConfig.getBitsPerSample =
      env->GetMethodID(jniBluetoothCodecConfigClass, "getBitsPerSample", "()I");
  android_bluetooth_BluetoothCodecConfig.getChannelMode =
      env->GetMethodID(jniBluetoothCodecConfigClass, "getChannelMode", "()I");
  android_bluetooth_BluetoothCodecConfig.getCodecSpecific1 = env->GetMethodID(
      jniBluetoothCodecConfigClass, "getCodecSpecific1", "()J");
  android_bluetooth_BluetoothCodecConfig.getCodecSpecific2 = env->GetMethodID(
      jniBluetoothCodecConfigClass, "getCodecSpecific2", "()J");
  android_bluetooth_BluetoothCodecConfig.getCodecSpecific3 = env->GetMethodID(
      jniBluetoothCodecConfigClass, "getCodecSpecific3", "()J");
  android_bluetooth_BluetoothCodecConfig.getCodecSpecific4 = env->GetMethodID(
      jniBluetoothCodecConfigClass, "getCodecSpecific4", "()J");

  method_onConnectionStateChanged =
      env->GetMethodID(clazz, "onConnectionStateChanged", "([BI)V");

  method_onAudioStateChanged =
      env->GetMethodID(clazz, "onAudioStateChanged", "([BI)V");

  method_onCodecConfigChanged =
      env->GetMethodID(clazz, "onCodecConfigChanged",
                       "([BLandroid/bluetooth/BluetoothCodecConfig;"
                       "[Landroid/bluetooth/BluetoothCodecConfig;"
                       "[Landroid/bluetooth/BluetoothCodecConfig;)V");

  method_isMandatoryCodecPreferred =
      env->GetMethodID(clazz, "isMandatoryCodecPreferred", "([B)Z");

  ALOGI("%s: succeeds", __func__);
}

static std::vector<btav_a2dp_codec_config_t> prepareCodecPreferences(
    JNIEnv* env, jobject object, jobjectArray codecConfigArray) {
  std::vector<btav_a2dp_codec_config_t> codec_preferences;

  int numConfigs = env->GetArrayLength(codecConfigArray);
  for (int i = 0; i < numConfigs; i++) {
    jobject jcodecConfig = env->GetObjectArrayElement(codecConfigArray, i);
    if (jcodecConfig == nullptr) continue;
    if (!env->IsInstanceOf(jcodecConfig,
                           android_bluetooth_BluetoothCodecConfig.clazz)) {
      ALOGE("%s: Invalid BluetoothCodecConfig instance", __func__);
      continue;
    }
    jint codecType = env->CallIntMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecType);
    jint codecPriority = env->CallIntMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecPriority);
    jint sampleRate = env->CallIntMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getSampleRate);
    jint bitsPerSample = env->CallIntMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getBitsPerSample);
    jint channelMode = env->CallIntMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getChannelMode);
    jlong codecSpecific1 = env->CallLongMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific1);
    jlong codecSpecific2 = env->CallLongMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific2);
    jlong codecSpecific3 = env->CallLongMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific3);
    jlong codecSpecific4 = env->CallLongMethod(
        jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific4);

    btav_a2dp_codec_config_t codec_config = {
        .codec_type = static_cast<btav_a2dp_codec_index_t>(codecType),
        .codec_priority =
            static_cast<btav_a2dp_codec_priority_t>(codecPriority),
        .sample_rate = static_cast<btav_a2dp_codec_sample_rate_t>(sampleRate),
        .bits_per_sample =
            static_cast<btav_a2dp_codec_bits_per_sample_t>(bitsPerSample),
        .channel_mode =
            static_cast<btav_a2dp_codec_channel_mode_t>(channelMode),
        .codec_specific_1 = codecSpecific1,
        .codec_specific_2 = codecSpecific2,
        .codec_specific_3 = codecSpecific3,
        .codec_specific_4 = codecSpecific4};

    codec_preferences.push_back(codec_config);
  }
  return codec_preferences;
}

static void initNative(JNIEnv* env, jobject object,
                       jint maxConnectedAudioDevices,
                       jobjectArray codecConfigArray,
                       jobjectArray codecOffloadingArray) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    ALOGE("%s: Bluetooth module is not loaded", __func__);
    return;
  }

  if (sBluetoothA2dpInterface != nullptr) {
    ALOGW("%s: Cleaning up A2DP Interface before initializing...", __func__);
    sBluetoothA2dpInterface->cleanup();
    sBluetoothA2dpInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    ALOGW("%s: Cleaning up A2DP callback object", __func__);
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    ALOGE("%s: Failed to allocate Global Ref for A2DP Callbacks", __func__);
    return;
  }

  android_bluetooth_BluetoothCodecConfig.clazz = (jclass)env->NewGlobalRef(
      env->FindClass("android/bluetooth/BluetoothCodecConfig"));
  if (android_bluetooth_BluetoothCodecConfig.clazz == nullptr) {
    ALOGE("%s: Failed to allocate Global Ref for BluetoothCodecConfig class",
          __func__);
    return;
  }

  sBluetoothA2dpInterface =
      (btav_source_interface_t*)btInf->get_profile_interface(
          BT_PROFILE_ADVANCED_AUDIO_ID);
  if (sBluetoothA2dpInterface == nullptr) {
    ALOGE("%s: Failed to get Bluetooth A2DP Interface", __func__);
    return;
  }

  std::vector<btav_a2dp_codec_config_t> codec_priorities =
      prepareCodecPreferences(env, object, codecConfigArray);

  std::vector<btav_a2dp_codec_config_t> codec_offloading =
      prepareCodecPreferences(env, object, codecOffloadingArray);

  bt_status_t status = sBluetoothA2dpInterface->init(
      &sBluetoothA2dpCallbacks, maxConnectedAudioDevices, codec_priorities,
      codec_offloading);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed to initialize Bluetooth A2DP, status: %d", __func__,
          status);
    sBluetoothA2dpInterface = nullptr;
    return;
  }
}

static void cleanupNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    ALOGE("%s: Bluetooth module is not loaded", __func__);
    return;
  }

  if (sBluetoothA2dpInterface != nullptr) {
    sBluetoothA2dpInterface->cleanup();
    sBluetoothA2dpInterface = nullptr;
  }

  env->DeleteGlobalRef(android_bluetooth_BluetoothCodecConfig.clazz);
  android_bluetooth_BluetoothCodecConfig.clazz = nullptr;

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static jboolean connectA2dpNative(JNIEnv* env, jobject object,
                                  jbyteArray address) {
  ALOGI("%s: sBluetoothA2dpInterface: %p", __func__, sBluetoothA2dpInterface);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));
  bt_status_t status = sBluetoothA2dpInterface->connect(bd_addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed A2DP connection, status: %d", __func__, status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean disconnectA2dpNative(JNIEnv* env, jobject object,
                                     jbyteArray address) {
  ALOGI("%s: sBluetoothA2dpInterface: %p", __func__, sBluetoothA2dpInterface);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));
  bt_status_t status = sBluetoothA2dpInterface->disconnect(bd_addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed A2DP disconnection, status: %d", __func__, status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean setSilenceDeviceNative(JNIEnv* env, jobject object,
                                       jbyteArray address, jboolean silence) {
  ALOGI("%s: sBluetoothA2dpInterface: %p", __func__, sBluetoothA2dpInterface);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);

  RawAddress bd_addr = RawAddress::kEmpty;
  if (addr) {
    bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));
  }
  if (bd_addr == RawAddress::kEmpty) {
    return JNI_FALSE;
  }
  bt_status_t status =
      sBluetoothA2dpInterface->set_silence_device(bd_addr, silence);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed A2DP set_silence_device, status: %d", __func__, status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean setActiveDeviceNative(JNIEnv* env, jobject object,
                                      jbyteArray address) {
  ALOGI("%s: sBluetoothA2dpInterface: %p", __func__, sBluetoothA2dpInterface);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);

  RawAddress bd_addr = RawAddress::kEmpty;
  if (addr) {
    bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));
  }
  bt_status_t status = sBluetoothA2dpInterface->set_active_device(bd_addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed A2DP set_active_device, status: %d", __func__, status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean setCodecConfigPreferenceNative(JNIEnv* env, jobject object,
                                               jbyteArray address,
                                               jobjectArray codecConfigArray) {
  ALOGI("%s: sBluetoothA2dpInterface: %p", __func__, sBluetoothA2dpInterface);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));
  std::vector<btav_a2dp_codec_config_t> codec_preferences =
      prepareCodecPreferences(env, object, codecConfigArray);

  bt_status_t status =
      sBluetoothA2dpInterface->config_codec(bd_addr, codec_preferences);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed codec configuration, status: %d", __func__, status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}
/************************************************
 * Savitech LHDC_EXT_API -- START
 ***********************************************/
static char *jByteArrayToChar(JNIEnv *env, jbyteArray buf) {
  char *chars = NULL;
  jbyte *bytes;
  bytes = env->GetByteArrayElements(buf, 0);
  if (!bytes) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }
  int chars_len = env->GetArrayLength(buf);
  chars = new char[chars_len + 1];
  memset(chars, 0, chars_len + 1);
  memcpy(chars, bytes, chars_len);
  chars[chars_len] = 0;
  env->ReleaseByteArrayElements(buf, bytes, 0);
  return chars;
}

static jint getLhdcCodecExtendApiVerNative(JNIEnv* env, jobject object,
                                           jbyteArray address,
                                           jbyteArray codecConfig){

  int status = BT_STATUS_FAIL;
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return status;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return status;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));

  char *chars = NULL;
  int chars_len = env->GetArrayLength(codecConfig);
  chars = jByteArrayToChar(env, codecConfig);
  if(!chars)
  {
    env->ReleaseByteArrayElements(address, addr, 0);
    return status;
  }

  status =
      sBluetoothA2dpInterface->getApiVer_lhdc(bd_addr, chars, chars_len);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed codec configuration, status: %d", __func__, status);
  }
  else {
    env->SetByteArrayRegion(codecConfig, 0, chars_len, reinterpret_cast<jbyte *>(chars));
  }

  env->ReleaseByteArrayElements(address, addr, 0);
  delete[] chars;

  return status;
}

static jint getLhdcCodecExtendApiConfigNative(JNIEnv* env, jobject object,
                                           jbyteArray address,
                                           jbyteArray codecConfig){

  int status = BT_STATUS_FAIL;
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return status;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return status;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));

  char *chars = NULL;
  int chars_len = env->GetArrayLength(codecConfig);
  chars = jByteArrayToChar(env, codecConfig);
  if(!chars)
  {
    env->ReleaseByteArrayElements(address, addr, 0);
    return status;
  }

  status =
      sBluetoothA2dpInterface->getApiCfg_lhdc(bd_addr, chars, chars_len);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed codec configuration, status: %d", __func__, status);
  }
  else {
    env->SetByteArrayRegion(codecConfig, 0, chars_len, reinterpret_cast<jbyte *>(chars));
  }

  env->ReleaseByteArrayElements(address, addr, 0);
  delete[] chars;

  return status;
}

static jint setLhdcCodecExtendApiConfigNative(JNIEnv* env, jobject object,
                                           jbyteArray address,
                                           jbyteArray codecConfig){

  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));

  char *chars = NULL;
  int chars_len = env->GetArrayLength(codecConfig);
  chars = jByteArrayToChar(env, codecConfig);

  int status =
      sBluetoothA2dpInterface->setApiCfg_lhdc(bd_addr, chars, chars_len);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Failed codec configuration, status: %d", __func__, status);
    return status;
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  delete[] chars;

  return status;
}

static void setLhdcCodecExtendApiDataNative(JNIEnv* env, jobject object,
                                           jbyteArray address,
                                           jbyteArray codecData){

  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sBluetoothA2dpInterface) {
    ALOGE("%s: Failed to get the Bluetooth A2DP Interface", __func__);
    return;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  RawAddress bd_addr;
  bd_addr.FromOctets(reinterpret_cast<const uint8_t*>(addr));

  char *chars = NULL;
  int chars_len = env->GetArrayLength(codecData);
  chars = jByteArrayToChar(env, codecData);

  sBluetoothA2dpInterface->setAPiData_lhdc(bd_addr, chars, chars_len);

  env->ReleaseByteArrayElements(address, addr, 0);
  delete[] chars;
}
//Savitech LHDC_EXT_API -- END

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void*)classInitNative},
    {"initNative",
     "(I[Landroid/bluetooth/BluetoothCodecConfig;[Landroid/bluetooth/BluetoothCodecConfig;)V",
     (void*)initNative},
    {"cleanupNative", "()V", (void*)cleanupNative},
    {"connectA2dpNative", "([B)Z", (void*)connectA2dpNative},
    {"disconnectA2dpNative", "([B)Z", (void*)disconnectA2dpNative},
    {"setSilenceDeviceNative", "([BZ)Z", (void*)setSilenceDeviceNative},
    {"setActiveDeviceNative", "([B)Z", (void*)setActiveDeviceNative},
    {"setCodecConfigPreferenceNative",
     "([B[Landroid/bluetooth/BluetoothCodecConfig;)Z",
     (void*)setCodecConfigPreferenceNative},
   // Savitech LHDC_EXT_API -- START
   {"getLhdcCodecExtendApiVerNative",
    "([B[B)I",
    (void*)getLhdcCodecExtendApiVerNative},
   {"getLhdcCodecExtendApiConfigNative",
    "([B[B)I",
    (void*)getLhdcCodecExtendApiConfigNative},
   {"setLhdcCodecExtendApiConfigNative",
    "([B[B)I",
    (void*)setLhdcCodecExtendApiConfigNative},
   {"setLhdcCodecExtendApiDataNative",
    "([B[B)V",
    (void*)setLhdcCodecExtendApiDataNative},
   // Savitech LHDC_EXT_API -- END
};

int register_com_android_bluetooth_a2dp(JNIEnv* env) {
  return jniRegisterNativeMethods(
      env, "com/android/bluetooth/a2dp/A2dpNativeInterface", sMethods,
      NELEM(sMethods));
}
}
