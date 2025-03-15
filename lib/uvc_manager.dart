
import 'uvc_manager_platform_interface.dart';

class UvcManager {
  Future<String?> getPlatformVersion() {
    return UvcManagerPlatform.instance.getPlatformVersion();
  }
}
