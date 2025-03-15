import 'package:flutter_test/flutter_test.dart';
import 'package:uvc_manager/uvc_manager.dart';
import 'package:uvc_manager/uvc_manager_platform_interface.dart';
import 'package:uvc_manager/uvc_manager_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockUvcManagerPlatform
    with MockPlatformInterfaceMixin
    implements UvcManagerPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final UvcManagerPlatform initialPlatform = UvcManagerPlatform.instance;

  test('$MethodChannelUvcManager is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelUvcManager>());
  });

  test('getPlatformVersion', () async {
    UvcManager uvcManagerPlugin = UvcManager();
    MockUvcManagerPlatform fakePlatform = MockUvcManagerPlatform();
    UvcManagerPlatform.instance = fakePlatform;

    expect(await uvcManagerPlugin.getPlatformVersion(), '42');
  });
}
