// Copyright (c) 2020-2026 saki t_saki@serenegiant.com
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

/// UACの音声設定情報
final class UACInfo {
  final int device_id;
  final int channels;
  final int resolution;
  final int sampling_freq;
  final int packet_bytes;

  UACInfo(
    this.device_id,
    this.channels,
    this.resolution,
    this.sampling_freq,
    this.packet_bytes
  );

  @override
  String toString() {
    return 'UACInfo{device_id: $device_id, channels: $channels, resolution: $resolution, sampling_freq: $sampling_freq, packet_bytes: $packet_bytes}';
  }
}