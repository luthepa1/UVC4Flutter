package com.serenegiant.usb
/**
 * aAndUsb
 * Copyright (c) 2014-2026 saki t_saki@serenegiant.com
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

import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.os.Handler
import android.util.Log
import androidx.annotation.Keep
import com.serenegiant.usb.DeviceDetector.DeviceDetectorCallback
import com.serenegiant.system.PermissionUtils
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UsbConnector
import com.serenegiant.utils.HandlerThreadHandler
import java.io.IOException

/**
 * USB関係のイベントの処理のためにContextが必要なので
 * DeviceDetectorのライフサイクルの処理も含めて
 * FragmentでDeviceDetectorを保持する
 * XXX 主にUnity/Flutterのプラグインやnative側だけで処理するアプリからの使用を想定する。
 * UnityのUnityPlayerActivityはフレームワークのActivityでサポートパッケージや
 * androidxのFragmentActivityではないためこのクラスでも旧来のフレームワークの
 * Fragmentを使う。
 */
@Keep
class DeviceDetectorFragment constructor() : Fragment() {
	private val mSync = Any()
	private val mDeviceDetector: DeviceDetector = DeviceDetector.createInstance()
	private val mConnectors: MutableMap<UsbDevice, UsbConnector> = HashMap()
	private var mUSBMonitor: USBMonitor? = null
	private var mAsyncHandler: Handler? = null

	@Deprecated("Deprecated in Java")
	@Suppress("deprecation")
	override fun onAttach(context: Context) {
		super.onAttach(context)
		if (DEBUG) Log.v(TAG, "onAttach:")
		synchronized(mSync) {
			mAsyncHandler = HandlerThreadHandler.createHandler(TAG)
		}
		mUSBMonitor = USBMonitor(context, mOnDeviceConnectListener)
		val args = arguments
		if (args != null) {
			val filters: List<DeviceFilter>? = args.getParcelableArrayList(
				ARGS_DEVICE_FILTERS
			)
			if (filters != null) {
				mUSBMonitor!!.setDeviceFilter(filters)
			}
		}
		mDeviceDetector.add(mDeviceDetectorCallback)
	}

	@Deprecated("Deprecated in Java")
	@Suppress("deprecation")
	override fun onStart() {
		super.onStart()
		if (DEBUG) Log.v(
			TAG, "onStart:hasCameraPermission=" + PermissionUtils.hasCamera(
				activity
			)
		)
		if (mUSBMonitor != null) {
			if (DEBUG) Log.v(
				TAG,
				"onStart:register USBMonitor," + mUSBMonitor!!.deviceList + "," + mUSBMonitor!!.deviceCount
			)
			mUSBMonitor!!.register()
			// After register(), explicitly re-scan for devices that are already
			// physically connected. USBMonitor.register() only wires up broadcast
			// receivers for future USB_DEVICE_ATTACHED intents — it does NOT call
			// onAttach() for devices already present. Since onStop() removed all
			// devices via removeDevice(), we must re-add them here or the camera
			// will never recover after app backgrounding (e.g. switching to Spotify
			// and back, screen lock, etc.).
			synchronized(mConnectors) {
				for (device in mUSBMonitor!!.deviceList.toList()) {
					if (!mConnectors.containsKey(device)) {
						if (DEBUG) Log.v(TAG, "onStart:re-scanning already-attached device:" + device.deviceName)
						if (mUSBMonitor!!.hasPermission(device)) {
							addDevice(device)
						} else {
							// Permission was lost (rare but possible) — re-request.
							bringToForeground()
							mUSBMonitor!!.requestPermission(device)
						}
					}
				}
			}
		}
	}

	@Deprecated("Deprecated in Java")
	@Suppress("deprecation")
	override fun onStop() {
		if (DEBUG) Log.v(TAG, "onStop:")
		if (mUSBMonitor != null) {
			if (DEBUG) Log.v(TAG, "onStop:unregister USBMonitor")
			mUSBMonitor!!.unregister()
		}
		// Replace clearAll() with per-device removal so that the native C++ layer sends
		// individual on_device_changed(false) messages to Dart for each attached device.
		// Previously, nativeClearAll() would silently clear native state without notifying
		// Dart. This created a race condition: if on_device_changed(true) arrived in Dart
		// (from nativeAdd() on onStart()) before the Dart UVCManager.resumed lifecycle
		// handler ran to clean up stale controllers, the new controller would be blocked
		// from being created (containsKey guard), causing openDevice() to never be called
		// and the camera to show a permanent black/no-device screen after returning from
		// background (screen lock, app switching, etc.).
		val devicesToRemove: List<UsbDevice>
		synchronized(mConnectors) {
			devicesToRemove = ArrayList(mConnectors.keys)
		}
		for (device in devicesToRemove) {
			if (DEBUG) Log.v(TAG, "onStop:removeDevice:${device.deviceName}")
			removeDevice(device)
		}
		super.onStop()
	}

	@Deprecated("Deprecated in Java")
	@Suppress("deprecation")
	override fun onDetach() {
		if (DEBUG) Log.v(TAG, "onDetach:")
		mDeviceDetector.remove(mDeviceDetectorCallback)
		if (mUSBMonitor != null) {
			mUSBMonitor!!.destroy()
			mUSBMonitor = null
		}
		synchronized(mSync) {
			if (mAsyncHandler != null) {
				try {
					mAsyncHandler!!.removeCallbacksAndMessages(null)
					mAsyncHandler!!.looper.quit()
				} catch (e: Exception) {
					if (DEBUG) Log.w(TAG, e)
				}
				mAsyncHandler = null
			}
		}
		super.onDetach()
	}

	//--------------------------------------------------------------------------------
	/**
	 * native側へ登録する
	 * パーミッションを保持していること
	 * @param device
	 */
	private fun addDevice(device: UsbDevice) {
		addDevice(device, retryCount = 0)
	}

	private fun addDevice(device: UsbDevice, retryCount: Int) {
		if (DEBUG) Log.v(TAG, "addDevice:" + device.deviceName + " (attempt ${retryCount + 1})")
		if (mUSBMonitor!!.hasPermission(device)) {
			try {
				val connector = mUSBMonitor!!.openDevice(device)
				synchronized(mConnectors) {
					mConnectors.put(device, connector)
				}
				mDeviceDetector.add(device, connector.fileDescriptor)
			} catch (e: IOException) {
				// IOException here usually means USB bus is still settling (e.g. long cable,
				// hub power droop).  Schedule a single retry after 500 ms so the device still
				// appears without requiring a physical replug.
				Log.w(TAG, "addDevice IOException for ${device.deviceName} (attempt ${retryCount + 1}): $e")
				if (retryCount < 3) {
					val handler = mAsyncHandler
					if (handler != null) {
						handler.postDelayed({
							// Guard: make sure the device is still attached and we still have permission
							if (mUSBMonitor != null && mUSBMonitor!!.hasPermission(device)) {
								addDevice(device, retryCount + 1)
							}
						}, 500L * (retryCount + 1))
					}
				} else {
					Log.e(TAG, "addDevice failed after ${retryCount + 1} attempts for ${device.deviceName} — giving up")
				}
			}
		}
	}

	/**
	 * native側から登録解除する
	 * @param device
	 */
	private fun removeDevice(device: UsbDevice) {
		if (DEBUG) Log.v(TAG, "removeDevice:" + device.deviceName)
		mDeviceDetector.remove(device)
		synchronized(mConnectors) {
			if (mConnectors.containsKey(device)) {
				val removed = mConnectors.remove(device)
				removed?.close()
			}
		}
	}

	@Suppress("deprecation")
	private fun bringToForeground() {
		val activity = activity
		if ((activity != null) && !activity.isFinishing) {
//			final Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
			val intent = Intent(activity, activity.javaClass)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			activity.startActivity(intent)
		}
	}

	private val mOnDeviceConnectListener: USBMonitor.Callback = object : USBMonitor.Callback {
		override fun onAttach(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onAttach:" + device.deviceName)
			if (mUSBMonitor!!.hasPermission(device)) {
				// すでにパーミッションを保持しているとき
				addDevice(device)
			} else {
				// パーミッションを保持していないとき
				// Bring the app to the foreground BEFORE requesting USB permission so
				// the system dialog appears on top of the Flutter activity rather than
				// behind it (observed on Android 10+ with multi-window / split-screen).
				bringToForeground()
				mUSBMonitor!!.requestPermission(device)
			}
		}

		override fun onPermission(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onPermission:" + device.deviceName)
			addDevice(device)
			// システムダイアログが表示されている状態でアプリ上に表示されているパーミッションダイアログで許可すると
			// システムダイアログが表示されたままになるのでアプリをフォアグラウンドへ移動させる
			bringToForeground()
		}

		override fun onConnected(
			device: UsbDevice,
			connector: UsbConnector
		) {
			if (DEBUG) Log.v(TAG, "Callback#onConnected:" + device.deviceName)
		}

		override fun onDisconnect(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onDisconnect:" + device.deviceName)
		}

		override fun onDetach(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onDetach:" + device.deviceName)
			removeDevice(device)
		}

		override fun onCancel(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onCancel:" + device.deviceName)
		}

		override fun onError(device: UsbDevice?, t: Throwable) {
			Log.w(TAG, "Callback#onError:", t)
		}
	}

	private val mDeviceDetectorCallback
		: DeviceDetectorCallback = object : DeviceDetectorCallback {
		override fun onRequestRefreshDevices() {
			if (DEBUG) Log.v(TAG, "onRequestRefreshDevices:")
			// native側からの接続機器一覧更新要求
			synchronized(mSync) {
				if (mAsyncHandler != null) {
					mAsyncHandler!!.post {
						mDeviceDetector.clearAll()
						if (mUSBMonitor != null && mUSBMonitor!!.isRegistered) {
							mUSBMonitor!!.refreshDevices()
						}
					}
				}
			}
		}

		override fun onRequestClaimInterfaces(
			device: UsbDevice, interfaces: List<UsbInterface?>
		): Boolean {
			if (DEBUG) Log.v(TAG, "onRequestClaimInterfaces:" + device.deviceName)
			var result = false

			synchronized(mConnectors) {
				if (mConnectors.containsKey(device)) {
					val connector = mConnectors[device]
					if (connector != null) {
						for (intf in interfaces) {
							connector.claimInterface(intf)
						}
						result = true
					}
				}
			}

			return result
		}

		override fun onRequestReleaseInterfaces(
			device: UsbDevice, interfaces: List<UsbInterface?>
		): Boolean {
			if (DEBUG) Log.v(TAG, "onRequestReleaseInterfaces:" + device.deviceName)
			var result = false

			synchronized(mConnectors) {
				if (mConnectors.containsKey(device)) {
					val connector = mConnectors[device]
					if (connector != null) {
						for (intf in interfaces) {
							connector.releaseInterface(intf)
						}
						result = true
					}
				}
			}

			return result
		}
	}

	init {
		if (DEBUG) Log.v(TAG, "コンストラクタ:")
		// Activity再生成時にもこのFragmentの再生成をしない
		retainInstance = true
	}

	companion object {
		private const val DEBUG = false // set false on production
		private val TAG: String = DeviceDetectorFragment::class.java.simpleName

		private const val ARGS_DEVICE_FILTERS = "ARGS_DEVICE_FILTERS"
	}
}
