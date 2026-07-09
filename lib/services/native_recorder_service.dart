import 'package:flutter/services.dart';
import 'package:math_studio/services/native_recorder_service.dart';

class NativeRecorderService {
  static const MethodChannel _channel = MethodChannel('math_studio/recorder');
  static const EventChannel _events = EventChannel(
    'math_studio/recorder_events',
  );
  static void listenEvents(void Function(String event) onEvent) {
    _events.receiveBroadcastStream().listen((event) {
      if (event is String) {
        onEvent(event);
      }
    });
  }

  static Future<void> startRecording({
    required String resolution,
    required int fps,
  }) async {
    final result = await _channel.invokeMethod('startRecording', {
      "resolution": resolution,
      "fps": fps,
    });

    print(result);
  }

  static Future<void> pauseRecording() async {
    await _channel.invokeMethod('pauseRecording');
  }

  static Future<void> resumeRecording() async {
    await _channel.invokeMethod('resumeRecording');
  }

  static Future<void> stopRecording() async {
    print("STOP BUTTON PRESSED");

    await _channel.invokeMethod('stopRecording');

    print("STOP SENT");
  }
}
