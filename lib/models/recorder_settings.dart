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

class RecordingResolution {
  final String key;
  final String label;
  final int? width;
  final int? height;

  const RecordingResolution({
    required this.key,
    required this.label,
    this.width,
    this.height,
  });

  static const all = [
    RecordingResolution(
      key: '720p',
      label: '1280 x 720',
      width: 1280,
      height: 720,
    ),
    RecordingResolution(
      key: '1080p',
      label: '1920 x 1080',
      width: 1920,
      height: 1080,
    ),
    RecordingResolution(
      key: '1440p',
      label: '2560 x 1440',
      width: 2560,
      height: 1440,
    ),
    RecordingResolution(
      key: 'original',
      label: 'Original Device Resolution',
    ),
  ];

  static RecordingResolution fromKey(String key) {
    return all.firstWhere(
      (resolution) => resolution.key == key,
      orElse: () => all[1],
    );
  }
}

class RecorderSettings {
  /// Native uchun: 720p / 1080p / 1440p / original
  String resolution;

  int fps;

  AudioMode audioMode;

  FloatingPanelMode floatingPanelMode;

  RecorderSettings({
    this.resolution = '1080p',
    this.fps = 60,
    this.audioMode = AudioMode.microphone,
    this.floatingPanelMode = FloatingPanelMode.compact,
  });
}
