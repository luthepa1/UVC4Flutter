// Verifies the Dart `flutter_device_info` FFI struct matches the native
// packed `usb_device_info_t` layout (aandusb_native.h).  Regression test for
// the missing `bcd_usb` field (2026-08-05): without it every field was read
// 2 bytes early, producing garbled descriptors (vid=0x534d0200, name="\x01")
// which broke UVC stable-UID pinning.
import 'dart:ffi' as ffi;
import 'dart:typed_data';

import 'package:ffi/ffi.dart' as pffi;
import 'package:flutter_test/flutter_test.dart';
import 'package:uvc_manager/src/uvcplugin_bindings_generated.dart';

void main() {
  test('flutter_device_info layout matches native usb_device_info_t', () {
    // Build the exact byte blob the native side would write (packed):
    //   offset 0:  uint16 bcd_usb   = 0x0200 (LE -> 00 02)
    //   offset 2:  uint32 vendor_id = 0x0000534d (LE -> 4d 53 00 00)
    //   offset 6:  uint32 product_id= 0x00000021 (LE -> 21 00 00 00)
    //   offset 10: uint8 device_class = 0xef
    //   offset 11: uint8 device_subclass = 0x02
    //   offset 12: uint8 device_protocol = 0x01
    //   offset 13: uint8 reserved1 = 0
    //   offset 14: uint8 name[128] = "/dev/bus/usb/001/005\0..."
    final name = '/dev/bus/usb/001/005';
    final total = 14 + 4 * 128; // 526
    final blob = Uint8List(total);
    final view = ByteData.view(blob.buffer);
    view.setUint16(0, 0x0200, Endian.little);
    view.setUint32(2, 0x0000534d, Endian.little);
    view.setUint32(6, 0x00000021, Endian.little);
    blob[10] = 0xef;
    blob[11] = 0x02;
    blob[12] = 0x01;
    blob[13] = 0x00;
    for (var i = 0; i < name.codeUnits.length; i++) {
      blob[14 + i] = name.codeUnits[i];
    }

    // Read the SAME memory through the generated (fixed) binding struct.
    final ptr = pffi.malloc<ffi.Uint8>(total);
    for (var i = 0; i < total; i++) {
      ptr[i] = blob[i];
    }
    final dart = ptr.cast<flutter_device_info>().ref;

    expect(dart.bcd_usb, 0x0200, reason: 'bcd_usb must be present at offset 0');
    expect(dart.vendor_id, 0x534d, reason: 'vendor_id must not be 0x534d0200');
    expect(dart.product_id, 0x0021, reason: 'product_id must not be 0x210000');
    final readName = String.fromCharCodes(
        List.generate(128, (i) => dart.name[i]).takeWhile((c) => c != 0));
    expect(readName, name,
        reason: 'name must be the real USB path, not a control char');

    pffi.malloc.free(ptr);
  });
}
