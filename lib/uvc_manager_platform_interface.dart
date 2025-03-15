import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'uvc_manager_method_channel.dart';

abstract class UvcManagerPlatform extends PlatformInterface {
  /// Constructs a UvcManagerPlatform.
  UvcManagerPlatform() : super(token: _token);

  static final Object _token = Object();

  static UvcManagerPlatform _instance = MethodChannelUvcManager();

  /// The default instance of [UvcManagerPlatform] to use.
  ///
  /// Defaults to [MethodChannelUvcManager].
  static UvcManagerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [UvcManagerPlatform] when
  /// they register themselves.
  static set instance(UvcManagerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
