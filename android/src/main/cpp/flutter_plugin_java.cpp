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

#define LOG_TAG "FlutterPluginJava"

#if 1	// デバッグ情報を出さない時は1
	#ifndef LOG_NDEBUG
		#define	LOG_NDEBUG		// LOGV/LOGD/MARKを出力しない時
	#endif
	#undef USE_LOGALL			// 指定したLOGxだけを出力
	#ifndef LOG_NDEBUG
		#define	LOG_NDEBUG		// LOGV/LOGD/MARKを出力しない時
	#endif
#else
	#define USE_LOGALL
	#define USE_LOGD
	#undef LOG_NDEBUG
	#undef NDEBUG
#endif

// android
#include <android/native_window.h>
#include <android/native_window_jni.h>
// std
#include <algorithm>
// aandusb
#include "utilbase.h"
// common
#include "common/jni_utils.h"
// flutter
#include "flutter_utils.h"
#include "flutter_uvc_holder.h"
#include "flutter_plugin_java.h"

namespace serenegiant::flutter {

/**
 * コンストラクタ
 */
/*private*/
FlutterPluginJava::FlutterPluginJava(jobject plugin_java)
:	plugin_java(plugin_java),
	m_manager(nullptr),
	holders()
{
	ENTER();

	m_manager = manager_init(this, on_device_attach, on_device_detach);

	EXIT();
}

/**
 * デストラクタ
 */
FlutterPluginJava::~FlutterPluginJava() noexcept {
	ENTER();

	terminate_all();

	if (m_manager) {
		manager_release(m_manager);
		m_manager = nullptr;
	}

	if (plugin_java) {
		AutoJNIEnv _env;
		JNIEnv *env = _env.get();
		if (env) {
			LOGD("delete global ref of plugin object");
			env->DeleteGlobalRef(plugin_java);
			env->ExceptionClear();
		} else {
			LOGE("Failed to get JNIEnv");
		}
		plugin_java = nullptr;
	}

	EXIT();
}

/**
 * 使用中のＵＶＣ機器があれば終了させる
 */
/*private*/
void FlutterPluginJava::terminate_all() {
	ENTER();
	
	std::lock_guard<std::mutex> lock(m_lock);

	LOGV("release holder(s)");
	for (const auto& iter: holders) {
		LOGD("stop&remove,%d", iter.first);
		FlutterUVCHolderSp holder = iter.second;
		if (holder) {
			holder->stop();
			holder.reset();
		}
	}
	holders.clear();
	device_path_by_id.clear();
	pending_device_paths.clear();
	android_device_info_cache.clear();

	EXIT();
}

/**
 * 指定したidに対応するUVCHolderSpを取得する
 * 存在していない場合にcreate_if_absent=trueならUVCHolderSpを生成する
 * @param device_id
 * @param create_if_absent
 * @return
 */
/*private*/
FlutterUVCHolderSp FlutterPluginJava::get_holder_locked(const int32_t &device_id, const bool &create_if_absent) {
	ENTER();

	LOGD("id=%d,create_if_absent=%d", device_id, create_if_absent);
	LOGD("num holders=%" FMT_SIZE_T, holders.size());

	FlutterUVCHolderSp result;
	auto iter = holders.find(device_id);
	if (iter != holders.end()) {
		LOGD("found");
		result = iter->second;
	} else if (create_if_absent) {
		LOGD("UVCHolder not found, create new");
		result = std::make_shared<FlutterUVCHolder>(m_manager, device_id);
		holders[device_id] = result;
	}

	RET(result);
}

void FlutterPluginJava::bind_device_path_locked(const int32_t &device_id, const std::string &device_path) {
	if (device_path.empty()) return;
	if (android_device_info_cache.find(device_path) == android_device_info_cache.end()) {
		return;
	}
	device_path_by_id[device_id] = device_path;
	pending_device_paths.erase(
		std::remove(pending_device_paths.begin(), pending_device_paths.end(), device_path),
		pending_device_paths.end());
}

std::string FlutterPluginJava::resolve_device_path_locked(const int32_t &device_id) {
	const auto mapped = device_path_by_id.find(device_id);
	if (mapped != device_path_by_id.end() &&
		android_device_info_cache.find(mapped->second) != android_device_info_cache.end()) {
		return mapped->second;
	}

	usb_device_info_t info;
	memset(&info, 0, sizeof(info));
	if (usb_get_device_info(m_manager, device_id, &info) == 0) {
		const std::string from_libusb(reinterpret_cast<const char*>(info.name));
		LOGI("resolve_device_path_locked: device_id=%d libusb_name=\"%s\" (len=%d) vid=0x%x pid=0x%x",
			device_id, from_libusb.c_str(), (int)from_libusb.length(),
			info.vendor_id, info.product_id);
		if (!from_libusb.empty() &&
			android_device_info_cache.find(from_libusb) != android_device_info_cache.end()) {
			bind_device_path_locked(device_id, from_libusb);
			return from_libusb;
		}
	} else {
		LOGW("resolve_device_path_locked: usb_get_device_info failed for device_id=%d", device_id);
	}

	LOGI("resolve_device_path_locked: device_id=%d searching pending_device_paths (size=%" FMT_SIZE_T ")",
		device_id, pending_device_paths.size());
	for (auto it = pending_device_paths.begin(); it != pending_device_paths.end(); ++it) {
		const auto &candidate = *it;
		LOGI("resolve_device_path_locked: pending candidate=\"%s\" in_cache=%d",
			candidate.c_str(),
			android_device_info_cache.find(candidate) != android_device_info_cache.end() ? 1 : 0);
		if (!candidate.empty() &&
			android_device_info_cache.find(candidate) != android_device_info_cache.end()) {
			device_path_by_id[device_id] = candidate;
			pending_device_paths.erase(it);
			LOGI("resolve_device_path_locked: bound device_id=%d to pending path=\"%s\"", device_id, candidate.c_str());
			return candidate;
		}
	}

	LOGW("resolve_device_path_locked: FAILED to resolve path for device_id=%d (cache_size=%" FMT_SIZE_T " pending_size=%" FMT_SIZE_T ")",
		device_id, android_device_info_cache.size(), pending_device_paths.size());
	return std::string();
}

/**
 * UVC機器が接続された時の処理
 * @param info
 */
/*private*/
int FlutterPluginJava::add(const int32_t &device_id) {
	ENTER();

	int result = -1;
	{
		std::lock_guard<std::mutex> lock(m_lock);
		const auto resolved_path = resolve_device_path_locked(device_id);
		if (!resolved_path.empty()) {
			LOGI("add: bound runtime device_id=%d to Android path=%s",
				device_id, resolved_path.c_str());
		} else {
			LOGW("add: unable to resolve Android path for runtime device_id=%d (cache size=%" FMT_SIZE_T ")",
				device_id, android_device_info_cache.size());
		}
		auto holder = get_holder_locked(device_id, true);
		// BUG-36: previously, result was set to 0 (success) whenever a
		// FlutterUVCHolder object existed at all — even if its constructor's
		// internal uvc_resize() call failed to actually claim the UVC
		// interface (e.g. garbled descriptors after a hub power droop).
		// That caused send_on_device_changed(true) to fire unconditionally,
		// telling Dart the camera was ready when it never actually attached,
		// which the watchdog then treated as "stuck" and looped forever.
		// Gate success on the real claim result instead.
		if (holder && !holder->claim_result()) {
			result = 0;
		} else if (holder) {
			LOGW("add: UVC claim failed for device_id=%d, err=%d — not notifying Dart",
				device_id, holder->claim_result());
			// Drop the failed holder so a subsequent attach attempt (e.g.
			// after a native/Kotlin USB reset) gets a fresh FlutterUVCHolder
			// rather than reusing this failed one.
			holders.erase(device_id);
		}
	}
	if (!result) {
		result = send_on_device_changed(device_id, true);
	}

	RETURN(result, int);
}

/**
 * UVC機器が取り外されたときの処理
 * @param device_id
 */
/*private*/
void FlutterPluginJava::remove(const int32_t &device_id) {
	ENTER();

	FlutterUVCHolderSp removed;
	{
		std::lock_guard<std::mutex> lock(m_lock);
		auto iter = holders.find(device_id);
		if (iter != holders.end()) {
			removed = iter->second;
			holders.erase(device_id);
		}
		const auto mapped = device_path_by_id.find(device_id);
		if (mapped != device_path_by_id.end()) {
			android_device_info_cache.erase(mapped->second);
			pending_device_paths.erase(
				std::remove(pending_device_paths.begin(), pending_device_paths.end(), mapped->second),
				pending_device_paths.end());
			device_path_by_id.erase(mapped);
		} else {
			// Fallback when no runtime->path mapping exists.
			usb_device_info_t info;
			memset(&info, 0, sizeof(info));
			if (usb_get_device_info(m_manager, device_id, &info) == 0) {
				std::string device_path(reinterpret_cast<const char*>(info.name));
				android_device_info_cache.erase(device_path);
				pending_device_paths.erase(
					std::remove(pending_device_paths.begin(), pending_device_paths.end(), device_path),
					pending_device_paths.end());
			}
		}
	}
	if (removed) {
		LOGD("remove %d", device_id);
		removed->stop();
		send_on_device_changed(device_id, false);
		LOGD("remove: finished");
	}

	EXIT();
}

/**
 * 指定したIDに対応するUVC機器が接続されていて利用可能かどうか
 * @param device_id
 * @return
 */
bool FlutterPluginJava::is_available(const int32_t &device_id) {
	ENTER();

	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}

	RETURN(holder != nullptr, bool);
}

/**
 * UVC機器との接続状態を取得する
 * @param device_id
 * @return
 */
device_state FlutterPluginJava::get_device_state(const int32_t &device_id) {
	ENTER();

	device_state result = DISCONNECTED;
	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}
	result = holder && holder->is_running() ? STREAMING : CONNECTED;

	RETURN(result, device_state);
}

usb_device_info_t FlutterPluginJava::get_device_info(const int32_t &device_id) {
	ENTER();

	usb_device_info_t result;
	// BUG-36 fix: Use usb_get_device_info to get the device path (name field),
	// then look up the Android descriptor cache by path.  Even when libusb
	// descriptors are garbled (vid=0x534d0200, product=""), the name field
	// (device path like "/dev/bus/usb/001/010") comes from the kernel and is
	// always correct.  If we find cached Android descriptors for this path,
	// override the garbled fields with the clean Android values.
	usb_get_device_info(m_manager, device_id, &result);

	// Resolve canonical path from runtime device_id first (source of truth),
	// then fall back to the libusb name field.
	std::string device_path;
	{
		std::lock_guard<std::mutex> lock(m_lock);
		device_path = resolve_device_path_locked(device_id);
		if (device_path.empty()) {
			device_path = std::string(reinterpret_cast<const char*>(result.name));
		}

		// Check if libusb descriptors look garbled (VID/PID > 0xFFFF or
		// name is not a valid /dev/bus/usb path).  If so, and we still
		// haven't found a cache entry, try a last-resort fallback: if
		// there's exactly ONE entry in the Android cache, assume it's
		// this device.  This handles the case where the prebuilt lib's
		// device_id mapping and the pending_device_paths queue got out
		// of sync due to a race between addDevice retries and the
		// on_device_attach callback.
		const bool libusb_garbled = result.vendor_id > 0xFFFF ||
			result.product_id > 0xFFFF ||
			(device_path.find("/dev/bus/usb/") != 0 && device_path.length() < 4);

		auto iter = android_device_info_cache.find(device_path);
		if (iter == android_device_info_cache.end() && libusb_garbled) {
			LOGW("get_device_info: cache miss + garbled libusb for id=%d — trying single-entry fallback", device_id);
			if (android_device_info_cache.size() == 1) {
				iter = android_device_info_cache.begin();
				device_path = iter->first;
				device_path_by_id[device_id] = device_path;
				LOGI("get_device_info: single-entry fallback — bound device_id=%d to path=\"%s\"", device_id, device_path.c_str());
			}
		}
		if (iter != android_device_info_cache.end()) {
			// Override garbled libusb fields with clean Android values.
			// Also override name/path because in some failure states libusb reports
			// "unknown" path, which triggers Dart watchdog invalid-path fallback.
			const auto &clean = iter->second;
			memcpy(result.name, clean.name, sizeof(result.name));
			result.vendor_id = clean.vendor_id;
			result.product_id = clean.product_id;
			result.device_class = clean.device_class;
			result.device_subclass = clean.device_subclass;
			result.device_protocol = clean.device_protocol;
			memcpy(result.manufacturer_name, clean.manufacturer_name, sizeof(result.manufacturer_name));
			memcpy(result.product_name, clean.product_name, sizeof(result.product_name));
			memcpy(result.serial, clean.serial, sizeof(result.serial));
			device_path_by_id[device_id] = device_path;
			LOGD("get_device_info: using cached Android descriptors for id=%d path=%s", device_id, device_path.c_str());
		} else {
			LOGW("get_device_info: no Android cache for id=%d path=\"%s\" (libusb vid=0x%x pid=0x%x name=\"%s\") — descriptors will be garbled",
				device_id, device_path.c_str(),
				result.vendor_id, result.product_id,
				reinterpret_cast<const char*>(result.name));
		}
	}

	RET(result);
}

void FlutterPluginJava::set_device_info(const std::string &device_path, const usb_device_info_t &info) {
	ENTER();

	std::lock_guard<std::mutex> lock(m_lock);
	android_device_info_cache[device_path] = info;
	pending_device_paths.erase(
		std::remove(pending_device_paths.begin(), pending_device_paths.end(), device_path),
		pending_device_paths.end());
	pending_device_paths.push_back(device_path);
	LOGI("set_device_info: cached Android descriptors for path=%s "
		 "(vid=0x%x pid=0x%x product=\"%s\")",
		 device_path.c_str(), info.vendor_id, info.product_id,
		 reinterpret_cast<const char*>(info.product_name));

	EXIT();
}

/**
 * 映像取得開始
 * レンダーコールバックを呼び出さないと実際には描画されない
 * @param device_id
 * @return
 */
/*public*/
int32_t FlutterPluginJava::start(const int32_t &device_id) {
	ENTER();

	int32_t  result = -1;
	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}
	if (holder) {
		result = holder->start();
	} else {
		LOGW("failed to get UVCHolder");
	}

	RETURN(result, int32_t);
}

/**
 * 映像取得終了
 * @param info
 * @return
 */
/*public*/
int32_t FlutterPluginJava::stop(const int32_t &device_id) {
	ENTER();

	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}
	if (holder) {
		holder->stop();
	}

	RETURN(0, int32_t);
}

/**
 * UVC機器からの映像サイズの変更要求
 * @param info
 * @param width
 * @param height
 * @return
 */
/*private*/
int32_t FlutterPluginJava::set_video_size(const int32_t &device_id,
	const uvc_raw_frame_t &frame_type,
	const uint32_t &width, const uint32_t &height) {

	ENTER();

	LOGV("id=%d,type=%d,sz(%dx%d)", device_id, frame_type, width, height);
	int result = -1;
	std::lock_guard<std::mutex> lock(m_lock);
	auto iter = holders.find(device_id);
	if (iter != holders.end()) {
		FlutterUVCHolderSp holder = iter->second;
		if (holder) {
			result = holder->set_video_size(frame_type, width, height);
		} else {
			LOGW("Failed to get UVCHolder");
		}
	} else {
		LOGW("UVCHolder not found, already detached?");
	}

	RETURN(result, int32_t);
}

/**
 * 現在の映像サイズ設定を取得
 * @param device_id
 * @param data 映像サイズ設定を書き込むためのflutter_video_size_t構造体へのポインタ
 * @return 0: 成功, 負: エラーコード
 */
int FlutterPluginJava::get_current_size(
	const int &device_id,
	uvc_video_size_t *data) {

	ENTER();

	int result = -1;
	if (LIKELY(data)) {
		std::lock_guard<std::mutex> lock(m_lock);
		auto iter = holders.find(device_id);
		if (iter != holders.end()) {
			auto &holder = iter->second;
			if (holder) {
				*data = holder->get_current_size();
				result = 0;
			} else {
				LOGW("Failed to get UVCHolder");
			}
		} else {
			LOGW("UVCHolder not found, already detached?");
		}
	}

	RETURN(result, int32_t);
}

/**
 * 映像取得用のSurfaceをセットする
 * @param device_id
 * @param tex_id
 * @param window nullable
 * @return
 */
int32_t FlutterPluginJava::set_preview_window(
	const int32_t &device_id,
	const int64_t &tex_id,
	ANativeWindow *window) {

	ENTER();

	LOGV("id=%d,tex_id=%" FMT_INT64_T ",window=%p", device_id, tex_id, window);
	int result = -1;
	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}
	if (holder) {
		result = holder->set_preview_surface(window);
	}


	RETURN(result, int32_t);
}

/**
 * コントロール機能でサポートしている機能を取得
 * @param device_id
 * @return
 */
/*public*/
uint64_t FlutterPluginJava::get_ctrl_supports(const int &device_id) {
	ENTER();

	if (LIKELY(device_id)) {
		FlutterUVCHolderSp holder = nullptr;
		if (m_lock.try_lock()) {
			holder = get_holder_locked(device_id, false);
			m_lock.unlock();
		}
		if (holder) {
			return holder->get_ctrl_supports();
		} else {
			LOGD("FlutterUVCHolder not found! id=%d", device_id);
		}
	}

	RETURN(0, uint64_t);
}

/**
 * プロセッシングユニットでサポートしている機能を取得
 * @param device_id
 * @return
 */
/*public*/
uint64_t FlutterPluginJava::get_proc_supports(const int &device_id) {
	ENTER();

	if (LIKELY(device_id)) {
		FlutterUVCHolderSp holder = nullptr;
		if (m_lock.try_lock()) {
			holder = get_holder_locked(device_id, false);
			m_lock.unlock();
		}
		if (holder) {
			return holder->get_proc_supports();
		} else {
			LOGD("FlutterUVCHolder not found! id=%d", device_id);
		}
	}

	RETURN(0, uint64_t);
}

/**
 * native側でUVC設定機能へアクセスするときのヘルパー関数
 * @param device_id
 * @param info
 * @return 0: 成功, 負: エラーコード
 */
/*public*/
int FlutterPluginJava::get_control_info(const int &device_id, uvc_control_info_t &info) {
	ENTER();

	int result = -5;
	if (LIKELY(device_id)) {
		FlutterUVCHolderSp holder = nullptr;
		if (m_lock.try_lock()) {
			holder = get_holder_locked(device_id, false);
			m_lock.unlock();
		}
		if (holder) {
			result = holder->get_control_info(info);
		} else {
			LOGD("FlutterUVCHolder not found! id=%d", device_id);
		}
	}

	RETURN(result, int);
}

/**
 * native側でUVC設定機能へアクセスするときのヘルパー関数
 * @param device_id
 * @param type
 * @param value
 * @return 0: 成功, 負: エラーコード
 */
/*public*/
int FlutterPluginJava::set_control_value(const int &device_id, const uint64_t &type, const int32_t &value) {
	ENTER();

	int result = -5;
	if (LIKELY(device_id)) {
		FlutterUVCHolderSp holder = nullptr;
		if (m_lock.try_lock()) {
			holder = get_holder_locked(device_id, false);
			m_lock.unlock();
		}
		if (holder) {
			result = holder->set_control_value(type, value);
		} else {
			LOGD("FlutterUVCHolder not found! id=%d", device_id);
		}
	}

	RETURN(result, int);
}

/**
 * native側でUVC設定機能へアクセスするときのヘルパー関数
 * @param device_id
 * @param type
 * @param value
 * @return 0: 成功, 負: エラーコード
 */
/*public*/
int FlutterPluginJava::get_control_value(const int &device_id, const uint64_t &type, int32_t &value) {
	ENTER();

	int result = -5;
	if (LIKELY(device_id)) {
		FlutterUVCHolderSp holder = nullptr;
		if (m_lock.try_lock()) {
			holder = get_holder_locked(device_id, false);
			m_lock.unlock();
		}
		if (holder) {
			result = holder->get_control_value(type, value);
		} else {
			LOGD("FlutterUVCHolder not found! id=%d", device_id);
		}
	}

	RETURN(result, int);
}

/**
 * native側でUVC映像サイズ設定へアクセスするときのヘルパー関数
 * @param device_id
 * @param index 映像サイズ設定のインデックス
 * @param num_supported 対応している映像サイズ設定の数を入れるuint32_tへのポインタ
 * @param data 映像サイズ設定を書き込むためのflutter_video_size_t構造体へのポインタ
 * @return 0: 成功, 負: エラーコード
 */
int FlutterPluginJava::get_supported_size(
	const int &device_id,
	const int32_t &index, int32_t *num_supported,
	uvc_video_size_t *data) {

	ENTER();

	int result = -5;
	FlutterUVCHolderSp holder = nullptr;
	if (m_lock.try_lock()) {
		holder = get_holder_locked(device_id, false);
		m_lock.unlock();
	}
	if (holder) {
		result = holder->get_supported_size(index, num_supported, data);
	} else {
		LOGD("FlutterUVCHolder not found! id=%d", device_id);
	}

	RETURN(result, int);
}

/*static, private*/
/**
 * USB機器が接続されたときのコールバック関数
 * @param callback_args UVCMainへのポインタ
 * @param device_id
 */
/*private,static*/
void FlutterPluginJava::on_device_attach(usb_manager_t*, void *callback_args, int32_t device_id) {
	ENTER();

	auto plugin = reinterpret_cast<FlutterPluginJava *>(callback_args);
	if (plugin) {
		plugin->add(device_id);
	}

	EXIT();
}

/**
 * USB機器が取り外されたときのコールバック関数
 * @param callback_args UVCMainへのポインタ
 * @param device_id
 */
void FlutterPluginJava::on_device_detach(usb_manager_t*, void *callback_args, int32_t device_id) {
	ENTER();

	auto plugin = reinterpret_cast<FlutterPluginJava *>(callback_args);
	if (plugin) {
		plugin->remove(device_id);
	}

	EXIT();
}

}	// namespace serenegiant::flutter
