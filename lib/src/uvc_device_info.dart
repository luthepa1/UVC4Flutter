// Copyright (c) 2020-2025 saki t_saki@serenegiant.com
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

// / USB機器情報を保持する
final class DeviceInfo {
  /// ベンダーID
  final int vendorId;
  /// プロダクトID
  final  int productId;
  /// デバイスクラス
  final int deviceClass;
  /// デバイスサブクラス
  final int deviceSubClass;
  /// デバイスプロトコル
  final  int deviceProtocol;
  /// パディング用のダミー
  final int reserved1;
  /// USB機器名(商品名ではない)
  final String name;
  /// 会社名(読み取れない機器は空文字列)
  final String manufacturerName;
  /// 製品名(読み取れない機器は空文字列)
  final String productName;
  /// シリアル番号(読み取れない機器は空文字列)
  final String serial;
  /// USB仕様バージョン(bcdUSB)。デバイス記述子の bcdUSB で、その機器が実際に
  /// 動いている速度帯を表す: 0x0300 以上 = SuperSpeed、0x0200 = High Speed。
  /// 0 は「ネイティブが値を返さなかった」を意味し、不明として扱うこと。
  final int bcdUsb;

  /// コンストラクタ
  DeviceInfo(this.vendorId, this.productId, this.deviceClass,
      this.deviceSubClass, this.deviceProtocol, this.reserved1, this.name,
      this.manufacturerName, this.productName, this.serial,
      {this.bcdUsb = 0});

  @override
  String toString() {
    return 'DeviceInfo{vendorId:$vendorId, productId:$productId, deviceClass:$deviceClass, deviceSubClass:$deviceSubClass, deviceProtocol:$deviceProtocol, reserved1:$reserved1, bcdUsb:0x${bcdUsb.toRadixString(16).padLeft(4, '0')}, name:$name, manufacturerName:$manufacturerName, productName:$productName, serial:$serial}';
  }
}
