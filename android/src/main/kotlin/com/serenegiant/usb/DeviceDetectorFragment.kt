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
	private var mPendingCloseCount: Int = 0 // BUG-21b: staggered close delay counter
	// BUG-43: Debounce reset/re-add storms for the same USB path.
	// Key = UsbDevice.deviceName (/dev/bus/usb/NNN/NNN)
	private val mLastResetByPathMs: MutableMap<String, Long> = HashMap()
	private val mResetInFlightPaths: MutableSet<String> = HashSet()

	companion object {
		private const val TAG = "DeviceDetectorFragment"
		private const val DEBUG = true
		private const val RESET_READD_COOLDOWN_MS = 10_000L

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
			// BUG-31/36/41: After a USB hub power-cycle, the MS210x may still be mid-
			// enumeration when we reach onStart().  Delay the re-scan by 4s to give
			// the USB bus time to stabilize.
			//
			// BUG-41 (2026-07-19): The previous BUG-36 fix always issued a
			// USBDEVFS_RESET before addDevice.  But when the device starts clean
			// (fresh hub plug), the reset DESTROYS the kernel's USB transfer buffer
			// allocation.  The re-enumerated device can't handle SETINTERFACE
			// (ENOMEM / errno 12) → "Failed to start stream,err=-99" → black screen.
			//
			// Fix: try addDevice FIRST without reset.  Only reset if the claim
			// fails (the addDevice IOException retry path handles this).  This
			// preserves the working kernel state for clean devices while still
			// recovering broken devices via the retry mechanism.
			val handler = mAsyncHandler
			if (handler != null) {
				handler.postDelayed({
					if (mUSBMonitor == null) return@postDelayed
					synchronized(mConnectors) {
						for (device in mUSBMonitor!!.deviceList.toList()) {
							if (!mConnectors.containsKey(device)) {
								if (DEBUG) Log.v(TAG, "onStart:re-scanning already-attached device:" + device.deviceName)
								if (mUSBMonitor!!.hasPermission(device)) {
									// BUG-41: Try addDevice directly first — no reset.
									// The addDevice IOException retry path will call
									// resetAndReAddDevice if the claim fails.
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
				}, 4000L)
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
	// ── USB bus reset (USBDEVFS_RESET ioctl) ─────────────────────────────
	// BUG-22: When the USB hub power-droops and restores, the MS210x
	// re-enumerates with corrupted USB descriptors (product="", name="\x01",
	// vid=0x534d0200).  The Dart-side gate (video_grab_manager.dart) catches
	// this and waits for clean re-enumeration, but the device never recovers
	// on its own — the USB host controller keeps the corrupted descriptors
	// cached until a proper USB bus reset forces the device to re-enumerate
	// from scratch.
	//
	// USBDEVFS_RESET (ioctl _IO('U', 20)) tells the kernel to issue a USB
	// reset on the device's port, which forces the device to re-enumerate
	// with clean descriptors.  This is the same mechanism the OS uses when
	// you physically replug a device.
	//
	// The actual ioctl is performed by nativeUsbReset() in
	// flutter_plugin_main.cpp (registered as a JNI native method on this
	// class at JNI_OnLoad time).
	//
	// We call this before addDevice() in rescanConnectedDevices() when a
	// device is not yet tracked in mConnectors (i.e. it was previously
	// rejected by the Dart-side garbled-descriptor gate or addDevice failed).
	private external fun nativeUsbReset(devicePath: String): Int

	// BUG-34: FD-based variant of nativeUsbReset.  On Android 16, SELinux blocks
	// direct open() on /dev/bus/usb/* for untrusted apps.  This variant accepts
	// a file descriptor obtained via USBMonitor.openDevice() (which goes through
	// the framework's UsbManager.openDevice()).  Does NOT close the FD — the
	// caller owns the connection lifecycle.
	private external fun nativeUsbResetFd(fd: Int): Int

	// BUG-36: Pass Android UsbDevice descriptor info to the native C++ layer
	// so that get_device_info returns clean Android descriptors instead of
	// garbled libusb descriptors after a hub power-cycle.
	private external fun nativeSetDeviceInfo(
		devicePath: String,
		vendorId: Int, productId: Int,
		deviceClass: Int, deviceSubclass: Int, deviceProtocol: Int,
		manufacturer: String?, product: String?, serial: String?
	): Int

	private fun resetUsbDevice(device: UsbDevice): Boolean {
		val deviceName = device.deviceName  // e.g. "/dev/bus/usb/001/010"
		if (deviceName.isNullOrEmpty()) return false

		return try {
			// BUG-34: Try the FD-based approach first.  On Android 16, SELinux
			// blocks direct open() on /dev/bus/usb/* for untrusted apps (avc:
			// denied { search } for name="usb" tclass=dir).  We open the device
			// via the framework's UsbManager.openDevice() (through USBMonitor)
			// which grants us a valid FD we can use for the USBDEVFS_RESET ioctl.
			// The connection is closed after the reset to avoid leaking FDs.
			val monitor = mUSBMonitor
			if (monitor != null && monitor.hasPermission(device)) {
				var connector: UsbConnector? = null
				try {
					connector = monitor.openDevice(device)
					val fd = connector.fileDescriptor
					if (fd >= 0) {
						val ret = nativeUsbResetFd(fd)
						if (ret == 0) {
							Log.i(TAG, "resetUsbDevice: USBDEVFS_RESET succeeded for $deviceName (fd=$fd)")
							return true
						} else {
							Log.w(TAG, "resetUsbDevice: nativeUsbResetFd failed for $deviceName: errno=$ret")
						}
					} else {
						Log.w(TAG, "resetUsbDevice: openDevice returned invalid fd=$fd for $deviceName")
					}
				} catch (e: IOException) {
					Log.w(TAG, "resetUsbDevice: openDevice IOException for $deviceName: ${e.message}")
				} finally {
					// Close the connection we opened for the reset.  This is safe
					// because we did NOT add this connector to mConnectors or pass
					// it to the native layer — it's a transient connection used
					// solely for obtaining the FD for the ioctl.
					if (connector != null) {
						try { connector.close() } catch (_: Exception) {}
						// Graveyard the transient connector to prevent its finalizer
						// from double-closing the FD after we already closed it.
						retainClosedConnector(
							connector,
							reason = "resetUsbDevice transient connection",
							deviceName = deviceName
						)
					}
				}
			}

			// Fallback: path-based approach (works on older Android versions
			// where SELinux doesn't block direct open() on USB device files).
			Log.i(TAG, "resetUsbDevice: falling back to path-based reset for $deviceName")
			val ret = nativeUsbReset(deviceName)
			if (ret == 0) {
				Log.i(TAG, "resetUsbDevice: USBDEVFS_RESET succeeded for $deviceName")
				true
			} else {
				Log.w(TAG, "resetUsbDevice: ioctl failed for $deviceName: errno=$ret")
				false
			}
		} catch (e: UnsatisfiedLinkError) {
			Log.w(TAG, "resetUsbDevice: native method not registered: ${e.message}")
			false
		} catch (e: Exception) {
			Log.w(TAG, "resetUsbDevice: failed for $deviceName: ${e.message}")
			false
		}
	}

	/**
	 * BUG-36: Reset the USB device, wait for kernel re-enumeration, refresh the
	 * device list so we get a fresh FD, then call addDevice.  After a
	 * USBDEVFS_RESET the old FD is invalidated, so the previously-cached
	 * UsbDevice/connection can no longer be used to claim the UVC interface
	 * (LIBUSB_ERROR_NO_DEVICE / err=-4).  Closing the connector, refreshing, and
	 * re-adding lets libusb open a brand-new FD against the re-enumerated device.
	 *
	 * Runs the reset synchronously on the calling thread, then posts the
	 * refresh+addDevice on the async handler after delayMs.
	 */
	private fun resetAndReAddDevice(device: UsbDevice, delayMs: Long) {
		val path = device.deviceName
		val nowMs = System.currentTimeMillis()
		synchronized(mSync) {
			if (mResetInFlightPaths.contains(path)) {
				Log.w(TAG, "resetAndReAddDevice: skip in-flight reset for $path")
				return
			}
			val lastMs = mLastResetByPathMs[path]
			if (lastMs != null && (nowMs - lastMs) < RESET_READD_COOLDOWN_MS) {
				Log.w(TAG, "resetAndReAddDevice: cooldown active for $path (${nowMs - lastMs}ms < ${RESET_READD_COOLDOWN_MS}ms) — skip")
				return
			}
			mResetInFlightPaths.add(path)
			mLastResetByPathMs[path] = nowMs
		}

		Log.i(TAG, "resetAndReAddDevice: $path (delay=${delayMs}ms)")
		resetUsbDevice(device)
		val handler = mAsyncHandler
		if (handler != null) {
			handler.postDelayed({
				try {
					if (mUSBMonitor == null) return@postDelayed
					try {
						mUSBMonitor!!.refreshDevices()
					} catch (e: Exception) {
						Log.w(TAG, "resetAndReAddDevice: refreshDevices failed: ${e.message}")
					}
					if (mUSBMonitor != null && mUSBMonitor!!.hasPermission(device) && !mConnectors.containsKey(device)) {
						addDevice(device)
					} else if (mUSBMonitor != null && !mUSBMonitor!!.hasPermission(device)) {
						bringToForeground()
						exitImmersiveMode()
						mUSBMonitor!!.requestPermission(device)
					}
				} finally {
					synchronized(mSync) { mResetInFlightPaths.remove(path) }
				}
			}, delayMs)
		} else {
			try {
				if (mUSBMonitor != null && mUSBMonitor!!.hasPermission(device) && !mConnectors.containsKey(device)) {
					addDevice(device)
				}
			} finally {
				synchronized(mSync) { mResetInFlightPaths.remove(path) }
			}
		}
	}

	//--------------------------------------------------------------------------------
	/**
	 * Re-scans all currently connected USB devices and attempts to add any that
	 * aren't already in mConnectors.  Called externally (via MethodChannel) when
	 * Dart detects that no camera is visible despite the USB bus being active —
	 * typically because the initial addDevice() attempt failed due to a slow
	 * enumeration on a long cable or hub power droop.
	 *
	 * BUG-22: When a device is not in mConnectors (previously failed addDevice
	 * or rejected by the Dart-side garbled-descriptor gate), we issue a
	 * USBDEVFS_RESET ioctl before re-adding it.  This forces the USB host
	 * controller to re-enumerate the device from scratch, clearing any
	 * corrupted descriptors from a hub power droop.  After the reset, we
	 * wait 500 ms for re-enumeration before calling addDevice().
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
					// BUG-41/43: Try addDevice first (no prophylactic reset).
					// addDevice() already has retry logic and only escalates to
					// resetAndReAddDevice on IOException. Unconditional reset in this
					// path can create reset storms that lead to SETINTERFACE ENOMEM
					// and black screen.
					addDevice(device)
				} else {
					// BUG-23: Device IS tracked in mConnectors, but its USB
					// descriptors may have been garbled by a hub power droop
					// AFTER it was originally added.  The Dart-side gate keeps
					// it in "re-enumerating" state, but the native side never
					// recovers because this branch just skips it.  Detect
					// garbled descriptors and force a reset + re-add.
					if (isDescriptorGarbled(device)) {
						Log.w(TAG, "rescanConnectedDevices: tracked device ${device.deviceName} has garbled descriptors — forcing reset + re-add")
						removeDevice(device)
						// BUG-36: Use 2s delay for kernel re-enumeration after reset.
						resetAndReAddDevice(device, 2000L)
					} else {
						if (DEBUG) Log.v(TAG, "rescanConnectedDevices: already tracked: ${device.deviceName}")
						// BUG-30/32: Do NOT call mDeviceDetector.add() to re-notify Dart.
						// The native DeviceDetector::add() destructs the old DeviceConnector
						// (closing the FD) before creating a new one, and that close races
						// with native unique_fd ownership → fdsan SIGABRT.  The
						// on_device_changed event lost at startup (because
						// dart_api_message_port was -1) cannot be safely re-sent this way.
						// The proper fix is to ensure the Dart UVCManager singleton is
						// constructed before the native addDevice runs, which is handled
						// by the 2s delay in onStart() + onAttach().
					}
				}
			}
		}
	}

	/// Detect garbled USB descriptors (same heuristic as the Dart-side gate in
	/// video_grab_manager.dart).  VID/PID > 0xFFFF or empty product + short
	/// device name indicates the USB host controller has cached corrupted
	/// descriptors from a hub power droop.
	private fun isDescriptorGarbled(device: UsbDevice): Boolean {
		return device.vendorId > 0xFFFF ||
			device.productId > 0xFFFF ||
			(device.productName.isNullOrEmpty() && device.deviceName.length < 4)
	}

	//--------------------------------------------------------------------------------
	// ── BUG-23: Force reset by device path ──────────────────────────────────
	// Called from the Dart side when the re-enumeration watchdog detects a
	// device has been stuck with garbled descriptors for >10 seconds.  The
	// Dart side knows the descriptors are garbled (FFI getDeviceInfo), but
	// the Kotlin UsbDevice object still has the original clean cached values.
	// This method finds the UsbDevice by its device path (e.g.
	// /dev/bus/usb/001/015), issues a USBDEVFS_RESET, removes it from
	// mConnectors, and re-adds it after 500ms.
	fun forceResetDeviceByPath(devicePath: String) {
		Log.i(TAG, "forceResetDeviceByPath: $devicePath")
		val monitor = mUSBMonitor ?: run {
			Log.w(TAG, "forceResetDeviceByPath: no USBMonitor")
			return
		}
		if (!monitor.isRegistered) {
			Log.w(TAG, "forceResetDeviceByPath: USBMonitor not registered")
			return
		}

		// Find the UsbDevice matching the path.  Android's UsbDevice.deviceName
		// is the /dev/bus/usb/NNN/NNN path.
		val target: UsbDevice? = monitor.deviceList.toList().find {
			it.deviceName == devicePath
		}
		if (target == null) {
			Log.w(TAG, "forceResetDeviceByPath: no device found at path $devicePath — falling back to forceResetAllUvcDevices")
			forceResetAllUvcDevices()
			return
		}

		Log.i(TAG, "forceResetDeviceByPath: found device ${target.deviceName}, removing + resetting + re-adding")
		synchronized(mConnectors) {
			// Remove from mConnectors and close the connector.
			removeDevice(target)
		}
		// BUG-36: Reset + refresh + re-add with fresh FD after delay.
		// (resetAndReAddDevice handles the USBDEVFS_RESET, refreshDevices, and
		// delayed addDevice; 2s is needed for kernel re-enumeration on MS210x.)
		resetAndReAddDevice(target, 2000L)
	}

	//--------------------------------------------------------------------------------
	// ── BUG-23 fallback: Force reset all UVC devices ──────────────────────
	// Called when the Dart side cannot provide a valid USB device path
	// (because the FFI getDeviceInfo() returned garbled descriptors with
	// a corrupted name field like "\x01").  This method iterates all
	// devices in the USBMonitor and resets any that match known UVC
	// VID/PIDs (MS210x 0x534D:0x0021) or that are already tracked in
	// mConnectors (could be garbled).
	fun forceResetAllUvcDevices() {
		Log.i(TAG, "forceResetAllUvcDevices: resetting all UVC devices")
		val monitor = mUSBMonitor ?: run {
			Log.w(TAG, "forceResetAllUvcDevices: no USBMonitor")
			return
		}
		if (!monitor.isRegistered) {
			Log.w(TAG, "forceResetAllUvcDevices: USBMonitor not registered")
			return
		}

		// Known UVC video grabber VID:PIDs
		val uvcVidPids = setOf(
			Pair(0x534D, 0x0021),  // MS210x (EasierCAP) — confirmed via live lsusb
		)

		var resetCount = 0
		synchronized(mConnectors) {
			for (device in monitor.deviceList.toList()) {
				val isUvc = uvcVidPids.contains(Pair(device.vendorId, device.productId))
				val isTracked = mConnectors.containsKey(device)
				// Reset if: it's a known UVC device, OR it's tracked (could be garbled)
				if (isUvc || isTracked) {
					Log.i(TAG, "forceResetAllUvcDevices: resetting ${device.deviceName} " +
						"(vid=0x${device.vendorId.toString(16)}, pid=0x${device.productId.toString(16)}, tracked=$isTracked)")
					// BUG-32: Do NOT call removeDevice() here — it triggers the native
					// DeviceDetector::remove → uvc_stop → Device::~Device() → close(FD)
					// which races with native unique_fd ownership → fdsan SIGABRT.
					// Instead, just reset the USB device.  The USBDEVFS_RESET will
					// cause a USB detach+reattach on the bus, which fires the natural
					// onDetach → removeDevice path safely via the async handler.
					// Also remove from mConnectors and graveyard the stale connector
					// without triggering the native remove path.  The USBDEVFS_RESET
					// will cause the kernel to invalidate the FD; the native unique_fd
					// will close it when the DeviceConnector is eventually destructed.
					// BUG-33: Do NOT call connector.close() — the native side owns the
					// FD.  Just graveyard it to prevent the GC finalizer double-close.
					val staleConnector = mConnectors.remove(device)
					if (staleConnector != null) {
						retainClosedConnector(
							staleConnector,
							reason = "forceResetAllUvcDevices (native owns FD)",
							deviceName = device.deviceName
						)
						synchronized(mClosedConnectorGraveyard) {
							mClosedConnectorGraveyard.add(staleConnector)
						}
					}
					// BUG-36: Reset + refresh + re-add with fresh FD after delay.
					resetAndReAddDevice(device, 2000L)
					resetCount++
				}
			}
		}
		if (resetCount == 0) {
			Log.w(TAG, "forceResetAllUvcDevices: no UVC devices found to reset")
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
		// BUG-44: Guard against duplicate add for a device already tracked.
		// addDevice can fire from both the USB_DEVICE_ATTACHED broadcast and the
		// onStart() delayed re-scan.  If we open a second UsbConnector for the
		// same device and call native DeviceDetector.add() again, the native
		// side constructs a second DeviceConnector over the same fd; when the
		// first DeviceConnector is destructed it closes the fd that the native
		// unique_fd (or the Java UsbDeviceConnection) still owns → fdsan SIGABRT
		// "attempted to close file descriptor N, actually owned by unique_fd"
		// (observed 2026-08-01, DeviceDetectorF thread, DeviceConnector::~DeviceConnector
		// → close_device during onStart re-scan).
		synchronized(mConnectors) {
			if (mConnectors.containsKey(device)) {
				if (DEBUG) Log.v(TAG, "addDevice: ${device.deviceName} already tracked — skipping duplicate add")
				return
			}
		}
		if (mUSBMonitor!!.hasPermission(device)) {
			var connector: UsbConnector? = null
			var storedInMap = false
			try {
				connector = mUSBMonitor!!.openDevice(device)
				synchronized(mConnectors) {
					mConnectors.put(device, connector)
				}
				storedInMap = true
				// BUG-36: Cache clean Android UsbDevice descriptors BEFORE nativeAdd.
				// The native get_device_info reads from libusb which returns garbled
				// descriptors after a hub power-cycle.  By caching the Android
				// UsbDevice values here, get_device_info can override the garbled
				// fields with clean ones keyed by the device path.
				try {
					nativeSetDeviceInfo(
						device.deviceName,
						device.vendorId, device.productId,
						device.deviceClass, device.deviceSubclass, device.deviceProtocol,
						device.manufacturerName, device.productName,
						device.serialNumber
					)
				} catch (e: Exception) {
					Log.w(TAG, "nativeSetDeviceInfo failed (non-fatal): ${e.message}")
				}
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
									// BUG-41: On first retry, use resetAndReAddDevice to
									// force a USBDEVFS_RESET + fresh FD.  The initial
									// addDevice failed (IOException from claim), so the
									// device may be in a stale kernel state that needs a
									// reset to clear.  Subsequent retries just retry addDevice.
									if (retryCount == 0) {
										resetAndReAddDevice(device, 2000L)
									} else {
										addDevice(device, retryCount + 1)
									}
								}
							}, 500L * (retryCount + 1))
						}
				} else {
					Log.e(TAG, "addDevice failed after ${retryCount + 1} attempts for ${device.deviceName} — giving up")
				}
			} catch (e: Exception) {
				Log.e(TAG, "addDevice unexpected exception for ${device.deviceName} (attempt ${retryCount + 1})", e)

				// BUG-33: Defensive cleanup for non-IOException paths so we never
				// leave a connector reachable only by GC finalizer.
				// When storedInMap == true, mDeviceDetector.add() was called, so the
				// native side may already own the FD.  Do NOT call close() — just
				// graveyard it to prevent the finalizer double-close.
				if (storedInMap) {
					val removed: UsbConnector? = synchronized(mConnectors) {
						mConnectors.remove(device)
					}
					if (removed != null) {
						retainClosedConnector(
							removed,
							reason = "addDevice unexpected exception after map insert",
							deviceName = device.deviceName
						)
						synchronized(mClosedConnectorGraveyard) {
							mClosedConnectorGraveyard.add(removed)
						}
						// Also tell native to release this device, since the add
						// may have partially succeeded on the native side.
						try { mDeviceDetector.remove(device) } catch (_: Exception) {}
					}
				} else if (connector != null) {
					// mDeviceDetector.add() was never reached, so Java still owns
					// the FD — safe to close() here.
					try { connector.close() } catch (_: Exception) {}
					retainClosedConnector(
						connector,
						reason = "addDevice unexpected exception before map insert",
						deviceName = device.deviceName
					)
				}
			}
		} else {
			// BUG-39 (2026-07-19): hasPermission returned false — silently
			// returned before, leaving the device stuck without a permission
			// request.  After a USBDEVFS_RESET, the device re-enumerates with
			// a new UsbDevice object and the permission grant may not carry
			// over.  Request permission here so the user gets the prompt.
			Log.w(TAG, "addDevice: no permission for ${device.deviceName} — requesting")
			bringToForeground()
			exitImmersiveMode()
			mUSBMonitor!!.requestPermission(device)
		}
	}

	/**
	* native側から登録解除する
	*
	 * BUG-21 (fdsan SIGABRT via Parcel-owned FD on rapid detach):
	 *
	 * When all USB devices detach simultaneously (e.g. car ignition off),
	 * the UsbDetector async thread fires onDetach() for each device in rapid
	 * succession.  nativeRemove(name) returns the FD to the native C++ side
	 * for cleanup, but the native Parcel that wrapped the FD may not have
	 * fully released ownership yet.  If we call UsbConnector.close()
	 * immediately after nativeRemove(), the close() races with the Parcel
	 * cleanup → fdsan detects "attempted to close file descriptor N, expected
	 * to be unowned, actually owned by Parcel" → SIGABRT.
	 *
	 * Fix: post the UsbConnector.close() + graveyard retention to the async
	 * handler with a staggered delay, giving the native C++ layer time to fully
	 * release the FD from its Parcel before the Java side closes it.  The
	 * connector is removed from mConnectors immediately so no new operations
	 * can target it, but the actual close() is deferred.
	 *
	 * BUG-21b: When ALL USB devices detach simultaneously (car ignition off),
	 * multiple removeDevice() calls fire within milliseconds.  Each schedules
	 * a 150ms delayed close, but they all fire at roughly the same time.  The
	 * native C++ layer processes FD releases sequentially, so by the time the
	 * later closes fire, the FDs may have been claimed by unique_fd on the
	 * native side.  Fix: use a longer base delay (300ms) and stagger each
	 * additional close by 100ms so they don't all fire at once.
	 *
	 * @param device
	 */
	private fun removeDevice(device: UsbDevice) {
		if (DEBUG) Log.v(TAG, "removeDevice:" + device.deviceName)
		// BUG-40 (2026-07-19): Do NOT call mDeviceDetector.remove(device) here.
		// On physical unplug, the native DeviceDetector::remove() → uvc_stop →
		// UVCCameraBase::~UVCCameraBase() → std::thread::~thread() path calls
		// std::terminate() if the UVC streaming thread is still joinable (running).
		// This is a bug in the prebuilt native library — uvc_stop doesn't wait
		// for the streaming thread to finish before destroying the UVCCameraBase.
		// The crash is a SIGABRT with abort message "terminating".
		//
		// Fix: just remove from mConnectors (Java-only) and graveyard the connector.
		// The native side will clean up when the FD becomes invalid (the device is
		// physically gone).  This is the same pattern used by forceResetAllUvcDevices
		// (BUG-32 fix) which already avoids mDeviceDetector.remove() for the same
		// class of native destructor crash.
		synchronized(mConnectors) {
			if (mConnectors.containsKey(device)) {
				val removed = mConnectors.remove(device)
				if (removed != null) {
					// BUG-33: Do NOT call UsbConnector.close() — the native side
					// owns the FD.  Just graveyard it to prevent the GC finalizer
					// double-close.
					retainClosedConnector(
						removed,
						reason = "removeDevice (native owns FD, BUG-40 skip native remove)",
						deviceName = device.deviceName
					)
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
				// BUG-31: After a USB hub power-cycle, the MS210x may still be
				// mid-enumeration when onAttach fires.  Calling addDevice()
				// immediately causes a claim failure (err=-4 / EBADF) that
				// corrupts the native device state and leaves descriptors
				// permanently garbled.  Delay addDevice() by 4s to give the
				// USB bus time to stabilize (increased from 2s — 2s was not
				// enough on the Lenovo TB373FU / Android 16, the UVC interface
				// claim still failed with LIBUSB_ERROR_NO_DEVICE).
				val handler = mAsyncHandler
				if (handler != null) {
					handler.postDelayed({
						if (mUSBMonitor != null && mUSBMonitor!!.hasPermission(device) && !mConnectors.containsKey(device)) {
							addDevice(device)
						}
					}, 4000L)
				} else {
					addDevice(device)
				}
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
