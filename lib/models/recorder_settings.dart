enum AudioMode {
  microphone,
  internal,
  both,
  off,
}

enum FloatingPanelMode {
  off,
  compact,
  expanded,
}

class RecorderSettings {
  /// Native uchun:
  /// 720p / 1080p / 1440p
  String resolution;

  int fps;

  AudioMode audioMode;

  FloatingPanelMode floatingPanelMode;

  RecorderSettings({
    this.resolution = "1080p",
    this.fps = 60,
    this.audioMode = AudioMode.microphone,
    this.floatingPanelMode = FloatingPanelMode.compact,
  });
}