/**
 * Copyright (c) 2020-2026 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.serenegiant.flutter.uvcplugin

import android.app.Activity
import android.util.Log
import android.util.LongSparseArray
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.core.util.forEach
import com.serenegiant.usb.DeviceDetector
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import java.lang.ref.WeakReference

/**
 * UVCManager
 */
@Keep
class UVCManager: FlutterPlugin, MethodCallHandler, ActivityAware {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var mChannel : MethodChannel
  private lateinit var mTextureRegistry: TextureRegistry
  private lateinit var mActivity: WeakReference<Activity>
  private val mSurfaceProducers = LongSparseArray<TextureRegistry.SurfaceProducer>()
  private var mNeedInitialize = false

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    if (DEBUG) Log.v(TAG, "onAttachedToEngine:")
    nativeInit()
    mTextureRegistry = flutterPluginBinding.textureRegistry
    mChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
    mChannel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    if (DEBUG) Log.v(TAG, "onDetachedFromEngine:")
    mChannel.setMethodCallHandler(null)
    nativeRelease()
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    if (DEBUG) Log.v(TAG, "onAttachedToActivity:")
    mActivity = WeakReference(binding.activity)
    mNeedInitialize = false
    // Always (re-)initialize the DeviceDetectorFragment on activity re-attach.
    // onDetachedFromActivity calls releaseDeviceDetector() which REMOVES the
    // fragment from the FragmentManager.  Previously, initUVCDeviceDetector()
    // was only called when mNeedInitialize was true (the race-condition path
    // where the Dart "initialize" method call arrived before the activity).
    // But in the normal app lifecycle (background → foreground), mNeedInitialize
    // was set to false on detach, so the fragment was never re-added — causing
    // rescanUvcDevices() / forceResetUvcDevice() to permanently fail with
    // "DeviceDetectorFragment not found" after every app resume.
    // initUVCDeviceDetector() is idempotent: it checks if the fragment already
    // exists before creating one, so calling it unconditionally is safe.
    if (DEBUG) Log.v(TAG, "onAttachedToActivity:initUVCDeviceDetector")
    DeviceDetector.initUVCDeviceDetector(binding.activity)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    if (DEBUG) Log.v(TAG, "onDetachedFromActivityForConfigChanges:")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    if (DEBUG) Log.v(TAG, "onReattachedToActivityForConfigChanges:")
    mActivity = WeakReference(binding.activity)
  }

  override fun onDetachedFromActivity() {
    if (DEBUG) Log.v(TAG, "onDetachedFromActivity:")
    mNeedInitialize = false
    releaseTextureAll()
    val a = mActivity.get()
    if (a != null) {
      if (DEBUG) Log.v(TAG, "onDetachedFromActivity:releaseDeviceDetector")
      DeviceDetector.releaseDeviceDetector(a)
    }
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    if (DEBUG) Log.v(TAG, "onMethodCall:${call.method}")
    when (call.method) {
      "initialize" -> {
        val a = mActivity.get()
        if (a != null) {
          if (DEBUG) Log.v(TAG, "onMethodCall#initialize:initUVCDeviceDetector")
          DeviceDetector.initUVCDeviceDetector(a)
        } else {
          mNeedInitialize = true
        }
      }
      "rescanDevices" -> {
        // Called by Dart when no camera devices are visible but the USB bus is
        // active.  Asks DeviceDetectorFragment to iterate mUSBMonitor.deviceList
        // and re-add any devices whose addDevice() previously failed silently
        // (e.g. IOException on initial USB enumeration with a long cable).
        val a = mActivity.get()
        if (a != null) {
          if (DEBUG) Log.v(TAG, "onMethodCall#rescanDevices")
          DeviceDetector.rescanUvcDevices(a)
          result.success(null)
        } else {
          result.error("No Activity", "Activity not available for rescan", null)
        }
      }
      "forceResetDevice" -> {
        // BUG-23: Called by Dart when a device is stuck in re-enumerating state
        // with garbled descriptors.  The Dart side detects the garbled state
        // via FFI getDeviceInfo(), but the native UsbDevice object still holds
        // the original clean descriptors (Android caches them at first enum).
        // This method forces a remove + USBDEVFS_RESET + re-add cycle on the
        // native side regardless of what UsbDevice reports.
        val devicePath = call.argument<String>("devicePath")
        if (devicePath != null) {
          if (DEBUG) Log.v(TAG, "onMethodCall#forceResetDevice: $devicePath")
          val a = mActivity.get()
          if (a != null) {
            // BUG-23 fix: If the device path is not a valid /dev/bus/usb/ path
            // (garbled FFI returned "\x01"), use the fallback that resets all
            // UVC devices instead of failing silently.
            // BUG-23b: "/dev/bus/usb/unknown/NNN" is the Dart-side fallback path
            // used when getDeviceInfo() returns garbled descriptors.  It passes
            // the plain startsWith check but will never match a real UsbDevice
            // (Android uses /dev/bus/usb/BBB/DDD).  Reject paths containing
            // "/unknown/" so forceResetAllUvcDevices is used as the fallback.
            if (devicePath.startsWith("/dev/bus/usb/") && !devicePath.contains("/unknown/")) {
              DeviceDetector.forceResetUvcDevice(a, devicePath)
            } else {
              Log.w(TAG, "onMethodCall#forceResetDevice: invalid path '$devicePath' — using forceResetAllUvcDevices fallback")
              DeviceDetector.forceResetAllUvcDevices(a)
            }
            result.success(null)
          } else {
            result.error("No Activity", "Activity not available for forceReset", null)
          }
        } else {
          result.error("missing devicePath", null, null)
        }
      }
      "forceResetAllUvcDevices" -> {
        // BUG-23 fallback: Reset all UVC devices when the Dart side cannot
        // identify a specific device by path.
        val a = mActivity.get()
        if (a != null) {
          if (DEBUG) Log.v(TAG, "onMethodCall#forceResetAllUvcDevices")
          DeviceDetector.forceResetAllUvcDevices(a)
          result.success(null)
        } else {
          result.error("No Activity", "Activity not available for forceResetAll", null)
        }
      }
      "createTexture" -> {
        val deviceId: Int? = call.argument("deviceId")
        val width: Int? = call.argument("width")
        val height: Int? = call.argument("height")
        if ((deviceId != null) && (width != null) && (height != null)) {
          result.success(createTexture(deviceId, width, height))
        } else {
          result.error("failed to get deviceId/width/height", null, null)
        }
      }
      "releaseTexture" -> {
        val deviceId: Int? = call.argument("deviceId")
        val texId: Any? = call.argument("textureId")
        val textureId = if (texId is Int) texId.toLong() else (texId as Long?)
        if ((deviceId != null) && (textureId != null)) {
          releaseTexture(deviceId, textureId)
        }
        result.success(null)
      }
      "keepScreenOn" -> {
        val activity = mActivity.get()
        if (activity != null) {
          val window = activity.window
          val onoff: Boolean? = call.argument("onoff")
          if (window != null) {
            activity.runOnUiThread {
              if (onoff == true) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
              } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
              }
            }
          }
          result.success(null)
          return
        }
        result.error("No Activity", null, null)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  //--------------------------------------------------------------------------------
  /**
   * Dart側からのメソッドコールの実体
   * Dart側のTextureで表示するためSurfaceTextureを生成しテクスチャidを返す
   * @param deviceId 対象とするUVC機器のid
   * @param width
   * @param height
   * @return テクスチャid
   */
  private fun createTexture(deviceId: Int, width: Int, height: Int): Long {
    if (DEBUG) Log.v(TAG, "createTexture:deviceId=${deviceId}/(${width}x${height})")
    try {
      val producer = mTextureRegistry.createSurfaceProducer()
      producer.setSize(width, height)
      mSurfaceProducers.append(producer.id(), producer)

      // Register a surface lifecycle callback so the native C++ layer is notified when
      // Flutter's rendering surface is destroyed (app backgrounded / screen locked) and
      // recreated (app foregrounded). Without this, the native layer keeps a stale/invalid
      // Surface reference after backgrounding and the Flutter Texture widget shows a
      // permanent black frame when the app returns to the foreground.
      producer.setCallback(object : TextureRegistry.SurfaceProducer.Callback {
        override fun onSurfaceAvailable() {
          if (DEBUG) Log.v(TAG, "SurfaceProducer.onSurfaceAvailable:deviceId=$deviceId,texId=${producer.id()}")
          // Surface (re)created — hand the new valid surface to native C++.
          nativeSetSurface(deviceId, producer.id(), producer.surface)
        }
        override fun onSurfaceDestroyed() {
          if (DEBUG) Log.v(TAG, "SurfaceProducer.onSurfaceDestroyed:deviceId=$deviceId,texId=${producer.id()}")
          // Surface is going away — clear the native C++ reference to avoid writing to an
          // invalid surface, which causes crashes / black frames on resume.
          nativeSetSurface(deviceId, producer.id(), null)
        }
      })

      // native側へSurfaceをセット (initial set — may be re-set via callback on resume)
      nativeSetSurface(deviceId, producer.id(), producer.surface)
      if (DEBUG) Log.v(TAG, "createTexture:producer=${producer}")
      return producer.id()
    } catch (e: Exception) {
      if (DEBUG) Log.w(TAG, e)
      throw e
    }
  }

  /**
   * Dart側からのメソッドコールの実体
   * Dart側でTextureを使って表示するのに使っていたテクスチャ/SurfaceTextureを破棄する
   * @param deviceId 対象とするUVC機器のid
   */
  private fun releaseTexture(deviceId: Int, textureId: Long) {
    if (DEBUG) Log.v(TAG, "releaseTexture:deviceId=${deviceId},textureId=${textureId}")
    nativeSetSurface(deviceId, -1, null)
    val producer = mSurfaceProducers.get(textureId)
    producer?.release()
    mSurfaceProducers.remove(textureId)
  }

  /**
   * テクスチャ/Surfaceが生成されていれば破棄する
   * activityからデタッチされるときに呼び出す
   */
  private fun releaseTextureAll() {
    if (DEBUG) Log.v(TAG, "releaseTextureAll:")
    mSurfaceProducers.forEach { _, producer ->
      producer.release()
    }
    mSurfaceProducers.clear()
  }

  @Keep
  external fun nativeInit(): Int

  @Keep
  external fun nativeRelease(): Int

  @Keep
  external fun nativeSetSurface(deviceId: Int, texId: Long, surface: Surface?): Int

  companion object {
    private const val DEBUG = true // set false on production
    private val TAG = UVCManager::class.java.simpleName

    private const val METHOD_CHANNEL_NAME = "com.serenegiant.flutter/aandusb_method"
    init {
      NativeLibLoader.loadNative()
    }
  }
}
