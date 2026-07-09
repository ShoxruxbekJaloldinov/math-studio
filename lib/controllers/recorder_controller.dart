import 'dart:async';
import 'package:flutter/material.dart';
import 'package:math_studio/services/native_recorder_service.dart';
import '../models/recorder_settings.dart';

class RecorderController extends ChangeNotifier {
  RecorderController() {
    NativeRecorderService.listenEvents((event) {
      if (event == "STOP") {
        timer?.cancel();

        isRecording = false;
        isPaused = false;
        seconds = 0;

        notifyListeners();
      } else if (event == "PAUSE") {
        isPaused = true;
        timer?.cancel();

        notifyListeners();
      } else if (event == "RESUME") {
        isPaused = false;
        timer?.cancel();

        timer = Timer.periodic(const Duration(seconds: 1), (_) {
          seconds++;
          notifyListeners();
        });

        notifyListeners();
      }
    });
  }
  final RecorderSettings settings = RecorderSettings();

  Timer? timer;

  int seconds = 0;

  bool isRecording = false;
  bool isPaused = false;

  Future<void> toggleRecording() async {
    //await NativeRecorderService.startRecording();

    isRecording = true;
    isPaused = false;
    seconds = 0;

    timer?.cancel();

    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      seconds++;
      notifyListeners();
    });

    notifyListeners();
  }

  Future<void> pauseRecording() async {
    await NativeRecorderService.pauseRecording();

    isPaused = true;

    timer?.cancel();

    notifyListeners();
  }

  Future<void> resumeRecording() async {
    await NativeRecorderService.resumeRecording();

    isPaused = false;

    timer?.cancel();

    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      seconds++;
      notifyListeners();
    });

    notifyListeners();
  }

  Future<void> stopRecording() async {
    timer?.cancel();

    await NativeRecorderService.stopRecording();

    isRecording = false;
    isPaused = false;
    seconds = 0;

    notifyListeners();
  }

  RecordingResolution get selectedResolution =>
      RecordingResolution.fromKey(settings.resolution);

  void setResolution(String resolution) {
    settings.resolution = resolution;
    notifyListeners();
  }

  void setFps(int fps) {
    settings.fps = fps;
    notifyListeners();
  }

  void setAudioMode(AudioMode mode) {
    settings.audioMode = mode;
    notifyListeners();
  }

  void setFloatingPanel(FloatingPanelMode mode) {
    settings.floatingPanelMode = mode;
    notifyListeners();
  }

  String get audioModeKey => settings.audioMode.name;

  String get audioText {
    switch (settings.audioMode) {
      case AudioMode.microphone:
        return "Microphone";
      case AudioMode.internal:
        return "Internal";
      case AudioMode.both:
        return "Mic + Internal";
      case AudioMode.off:
        return "Off";
    }
  }

  String get floatingPanelText {
    switch (settings.floatingPanelMode) {
      case FloatingPanelMode.off:
        return "Off";
      case FloatingPanelMode.compact:
        return "Compact";
      case FloatingPanelMode.expanded:
        return "Expanded";
    }
  }

  String get formattedTime {
    final h = (seconds ~/ 3600).toString().padLeft(2, '0');
    final m = ((seconds % 3600) ~/ 60).toString().padLeft(2, '0');
    final s = (seconds % 60).toString().padLeft(2, '0');

    return "$h:$m:$s";
  }
}
