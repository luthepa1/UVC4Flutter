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
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import androidx.annotation.Keep
import androidx.core.view.WindowInsetsControllerCompat
import com.serenegiant.usb.DeviceDetector.DeviceDetectorCallback
import com.serenegiant.system.PermissionUtils
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UsbConnector
import com.serenegiant.utils.HandlerThreadHandler
import java.io.IOException
import kotlin.math.max

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
	// fdsan graveyard — hold strong refs to UsbConnector objects that have been
	// explicitly closed via removeDevice().  The serenegiant libcommon UsbConnector
	// has a finalize() that calls close() unconditionally; if the GC runs after we
	// already closed the underlying UsbDeviceConnection FD, the finalizer
	// double-closes the (now-reused) FD and bionic's fdsan SIGABRTs the process
	// (seen in logcat as: FinalizerDaemon → UsbConnector.finalize → usb_device_close
	// → fdsan: attempted to close file descriptor N, expected to be unowned).
	//
	// This is a companion-object (process-lifetime) graveyard, NOT an instance
	// field. The previous instance-field graveyard was cleared in onDetach(),
	// but the GC could run after fragment destruction and still finalize the
	// connectors — the crash logcat (2026-07-11) shows FinalizerDaemon triggering
	// 6 seconds after removeDevice closed the connector. A process-lifetime
	// graveyard ensures the finalizer never runs until the process exits.
	// We cap it to 32 entries to avoid unbounded memory growth across many
	// open/close cycles; once full, the oldest entry is released (its FD is
	// long-closed by now, and the OS will have moved on to new FDs).
	private val mClosedConnectorGraveyard: MutableList<UsbConnector> = mutableListOf()

	companion object {
		private const val TAG = "DeviceDetectorFragment"
		private const val DEBUG = false

		// Process-lifetime graveyard for closed UsbConnector objects.
		// See the comment on mClosedConnectorGraveyard above for the full
		// explanation of the fdsan double-close crash.
		private val sGlobalGraveyard: MutableList<UsbConnector> = mutableListOf()
		private val sRetainReasonCounts: MutableMap<String, Int> = mutableMapOf()
		private var sRetainTotalCount: Int = 0
		private const val RETAIN_SUMMARY_EVERY = 25
		private const val RETAIN_STORM_THRESHOLD = 100
		private const val RETAIN_STORM_WINDOW_MS = 30_000L
		private const val RETAIN_STORM_WARN_COOLDOWN_MS = 10_000L
		private val sRetainTimestampsMs: ArrayDeque<Long> = ArrayDeque()
		private var sLastStormWarnAtMs: Long = 0L

		/// Add a closed UsbConnector to the process-lifetime graveyard so its
		/// finalizer cannot run and double-close the already-closed FD.
		private fun retainClosedConnector(
			connector: UsbConnector,
			reason: String,
			deviceName: String? = null
		) {
			synchronized(sGlobalGraveyard) {
				sGlobalGraveyard.add(connector)
				sRetainTotalCount += 1
				val updatedReasonCount = (sRetainReasonCounts[reason] ?: 0) + 1
				sRetainReasonCounts[reason] = updatedReasonCount

				val nowMs = System.currentTimeMillis()
				sRetainTimestampsMs.addLast(nowMs)
				while (sRetainTimestampsMs.isNotEmpty() &&
					(nowMs - sRetainTimestampsMs.first()) > RETAIN_STORM_WINDOW_MS) {
					sRetainTimestampsMs.removeFirst()
				}
				val retainInWindow = sRetainTimestampsMs.size
				Log.i(
					TAG,
					"retainClosedConnector: reason=$reason device=${deviceName ?: "unknown"} " +
						"connectorHash=${System.identityHashCode(connector)} globalSize=${sGlobalGraveyard.size} " +
						"reasonCount=$updatedReasonCount total=$sRetainTotalCount inWindow30s=$retainInWindow"
				)

				if (retainInWindow >= RETAIN_STORM_THRESHOLD) {
					val timeSinceLastWarn = nowMs - sLastStormWarnAtMs
					if (timeSinceLastWarn >= RETAIN_STORM_WARN_COOLDOWN_MS) {
						sLastStormWarnAtMs = nowMs
						val summary = sRetainReasonCounts.entries
							.sortedByDescending { it.value }
							.joinToString(", ") { "${it.key}=${it.value}" }
						val oldestAgeMs = if (sRetainTimestampsMs.isNotEmpty()) {
							max(0L, nowMs - sRetainTimestampsMs.first())
						} else {
							0L
						}
						Log.w(
							TAG,
							"retainClosedConnector STORM: retains=$retainInWindow within ${RETAIN_STORM_WINDOW_MS}ms " +
								"(oldestAgeMs=$oldestAgeMs, threshold=$RETAIN_STORM_THRESHOLD) " +
								"globalSize=${sGlobalGraveyard.size} total=$sRetainTotalCount byReason=[$summary]"
						)
					}
				}

				if (sRetainTotalCount % RETAIN_SUMMARY_EVERY == 0) {
					val summary = sRetainReasonCounts.entries
						.sortedByDescending { it.value }
						.joinToString(", ") { "${it.key}=${it.value}" }
					Log.i(
						TAG,
						"retainClosedConnector summary: total=$sRetainTotalCount globalSize=${sGlobalGraveyard.size} byReason=[$summary]"
					)
				}
			}
		}

		private const val ARGS_DEVICE_FILTERS = "ARGS_DEVICE_FILTERS"
	}

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
							exitImmersiveMode()
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
		// Release the instance graveyard — the fragment is being destroyed.
		// The global graveyard (sGlobalGraveyard) is process-lifetime and NOT
		// cleared here, because the GC may still run after onDetach and the
		// finalizer must not double-close the FDs. The instance graveyard is
		// redundant with the global one, so clearing it is safe.
		synchronized(mClosedConnectorGraveyard) {
			mClosedConnectorGraveyard.clear()
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
	 * Re-scans all currently connected USB devices and attempts to add any that
	 * aren't already in mConnectors.  Called externally (via MethodChannel) when
	 * Dart detects that no camera is visible despite the USB bus being active —
	 * typically because the initial addDevice() attempt failed due to a slow
	 * enumeration on a long cable or hub power droop.
	 *
	 * Safe to call at any time; runs on the calling thread (main thread via the
	 * UVCManager MethodChannel handler).  Acquires mConnectors lock internally.
	 */
	fun rescanConnectedDevices() {
		if (DEBUG) Log.v(TAG, "rescanConnectedDevices:")
		val monitor = mUSBMonitor ?: return
		if (!monitor.isRegistered) {
			Log.w(TAG, "rescanConnectedDevices: USBMonitor not registered — skip")
			return
		}
		synchronized(mConnectors) {
			for (device in monitor.deviceList.toList()) {
				if (!mConnectors.containsKey(device)) {
					if (DEBUG) Log.v(TAG, "rescanConnectedDevices: re-adding device: ${device.deviceName}")
					if (monitor.hasPermission(device)) {
						addDevice(device)
					} else {
						bringToForeground()
						exitImmersiveMode()
						monitor.requestPermission(device)
					}
				} else {
					if (DEBUG) Log.v(TAG, "rescanConnectedDevices: already tracked: ${device.deviceName}")
				}
			}
		}
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
			var connector: UsbConnector? = null
			var storedInMap = false
			try {
				connector = mUSBMonitor!!.openDevice(device)
				synchronized(mConnectors) {
					mConnectors.put(device, connector)
				}
				storedInMap = true
				mDeviceDetector.add(device, connector.fileDescriptor)
			} catch (e: IOException) {
				// IOException here usually means USB bus is still settling (e.g. long cable,
				// hub power droop).  Schedule a single retry after 500 ms so the device still
				// appears without requiring a physical replug.
				Log.w(TAG, "addDevice IOException for ${device.deviceName} (attempt ${retryCount + 1}): $e")

				// If the connector was opened but not stored in mConnectors (exception
				// between openDevice and mConnectors.put), close it and retain it in the
				// graveyard to prevent the fdsan double-close from its finalizer.
				if (connector != null && !storedInMap) {
					try { connector.close() } catch (_: Exception) {}
					retainClosedConnector(
						connector,
						reason = "addDevice IOException before map insert",
						deviceName = device.deviceName
					)
				}

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
			} catch (e: Exception) {
				Log.e(TAG, "addDevice unexpected exception for ${device.deviceName} (attempt ${retryCount + 1})", e)

				// Defensive cleanup for non-IOException paths so we never leave a
				// connector reachable only by GC finalizer.
				if (storedInMap) {
					val removed: UsbConnector? = synchronized(mConnectors) {
						mConnectors.remove(device)
					}
					if (removed != null) {
						try { removed.close() } catch (_: Exception) {}
						retainClosedConnector(
							removed,
							reason = "addDevice unexpected exception after map insert",
							deviceName = device.deviceName
						)
					}
				} else if (connector != null) {
					try { connector.close() } catch (_: Exception) {}
					retainClosedConnector(
						connector,
						reason = "addDevice unexpected exception before map insert",
						deviceName = device.deviceName
					)
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
				// Retain a strong reference in the process-lifetime graveyard so
				// the GC finalizer (UsbConnector.finalize) cannot run and
				// double-close the already-closed FD — fdsan SIGABRT.
				if (removed != null) {
					retainClosedConnector(
						removed,
						reason = "removeDevice normal detach",
						deviceName = device.deviceName
					)
					// Also keep in instance graveyard for backward compat.
					synchronized(mClosedConnectorGraveyard) {
						mClosedConnectorGraveyard.add(removed)
					}
				}
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

	// ── Immersive-mode management ──────────────────────────────────────────
	// The host app runs in SystemUiMode.immersiveSticky (set from Dart).  On
	// some devices (Lenovo TB373FU / Android 16) the system USB-permission
	// dialog appears BEHIND the fullscreen activity, making it invisible.
	// These helpers temporarily show the system bars so the dialog is visible,
	// then re-hide them after the user responds.
	private var immersiveRestoreHandler: Handler? = Handler(Looper.getMainLooper())

	private fun exitImmersiveMode() {
		val activity = activity ?: return
		// Must run on the main thread — WindowInsetsControllerCompat touches
		// the view hierarchy, which is only allowed from the thread that
		// created it.  The USBMonitor.Callback.onAttach fires on the
		// UsbDetector async handler thread, so without runOnUiThread the
		// call silently fails with "Only the original thread that created
		// a view hierarchy can touch its views" and the system bars are
		// never shown — leaving the USB permission dialog invisible behind
		// the fullscreen immersive activity (Lenovo TB373FU / Android 16).
		activity.runOnUiThread {
			try {
				val window = activity.window ?: return@runOnUiThread
				val controller = WindowInsetsControllerCompat(window, window.decorView)
				controller.show(WindowInsets.Type.systemBars())
				if (DEBUG) Log.d(TAG, "exitImmersiveMode: showed system bars for USB permission dialog")
			} catch (e: Exception) {
				Log.w(TAG, "exitImmersiveMode failed: ${e.message}")
			}
		}
	}

	private fun restoreImmersiveMode() {
		try {
			val activity = activity ?: return
			val window = activity.window ?: return
			val controller = WindowInsetsControllerCompat(window, window.decorView)
			controller.hide(WindowInsets.Type.systemBars())
			controller.systemBarsBehavior =
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			if (DEBUG) Log.d(TAG, "restoreImmersiveMode: re-hid system bars")
		} catch (e: Exception) {
			Log.w(TAG, "restoreImmersiveMode failed: ${e.message}")
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
				// Also exit immersive mode so the dialog is not hidden behind the
				// fullscreen activity on devices like Lenovo TB373FU / Android 16.
				bringToForeground()
				exitImmersiveMode()
				mUSBMonitor!!.requestPermission(device)
			}
		}

		override fun onPermission(device: UsbDevice) {
			if (DEBUG) Log.v(TAG, "Callback#onPermission:" + device.deviceName)
			addDevice(device)
			// システムダイアログが表示されている状態でアプリ上に表示されているパーミッションダイアログで許可すると
			// システムダイアログが表示されたままになるのでアプリをフォアグラウンドへ移動させる
			bringToForeground()
			// Restore immersive mode after the dialog is dismissed (delayed so
			// the dialog dismiss animation completes before we re-hide bars).
			immersiveRestoreHandler?.postDelayed({ restoreImmersiveMode() }, 300)
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
}
