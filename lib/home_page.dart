import 'package:flutter/material.dart';
import 'widgets/record_button.dart';
import 'widgets/setting_card.dart';
import 'package:provider/provider.dart';
import 'controllers/recorder_controller.dart';
import 'widgets/control_panel.dart';
import 'models/recorder_settings.dart';
import 'widgets/floating_panel.dart';
import 'widgets/floating_bubble.dart';
import 'widgets/floating_overlay.dart';
import 'services/native_recorder_service.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  bool showFloatingPanel = false;
  @override
  Widget build(BuildContext context) {
    final controller = Provider.of<RecorderController>(context);
    return Scaffold(
      appBar: AppBar(title: const Text("Screen Recorder"), centerTitle: true),
      body: Stack(
        children: [
          Container(
            width: double.infinity,
            height: double.infinity,
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Color(0xFF0F172A),
                  Color(0xFF111827),
                  Color(0xFF000000),
                ],
              ),
            ),
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  RecordButton(
                    isRecording: controller.isRecording,
                    onTap: () async {
                      if (!controller.isRecording) {
                        await NativeRecorderService.startRecording(
                          resolution: controller.settings.resolution,
                          fps: controller.settings.fps,
                        );
                        controller.toggleRecording();
                      }
                    },
                  ),
                  const SizedBox(height: 30),
                  Text(
                    controller.isRecording ? "Recording..." : "Start Recording",
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (controller.isRecording) ...[
                    const SizedBox(height: 12),
                    Text(
                      controller.formattedTime,
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                  const SizedBox(height: 25),

                  if (controller.isRecording)
                    ControlPanel(
                      isPaused: controller.isPaused,
                      onPause: () {
                        controller.pauseRecording();
                      },
                      onResume: () {
                        controller.resumeRecording();
                      },
                      onStop: () {
                        controller.stopRecording();
                      },
                    ),
                  const SizedBox(height: 40),

                  SettingCard(
                    icon: Icons.videocam_outlined,
                    title: "Resolution",
                    value: controller.settings.resolution,
                    onTap: () {
                      showModalBottomSheet(
                        context: context,
                        builder: (context) {
                          return SafeArea(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  title: const Text("1280 × 720"),
                                  onTap: () {
                                    controller.setResolution("1280 × 720");
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("1920 × 1080"),
                                  onTap: () {
                                    controller.setResolution("1920 × 1080");
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("2560 × 1440"),
                                  onTap: () {
                                    controller.setResolution("2560 × 1440");
                                    Navigator.pop(context);
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      );
                    },
                  ),

                  SettingCard(
                    icon: Icons.speed,
                    title: "FPS",
                    value: controller.settings.fps.toString(),
                    onTap: () {
                      showModalBottomSheet(
                        context: context,
                        builder: (context) {
                          return SafeArea(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  title: const Text("30 FPS"),
                                  onTap: () {
                                    controller.setFps(30);
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("60 FPS"),
                                  onTap: () {
                                    controller.setFps(60);
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("90 FPS"),
                                  onTap: () {
                                    controller.setFps(90);
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("120 FPS"),
                                  onTap: () {
                                    controller.setFps(120);
                                    Navigator.pop(context);
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      );
                    },
                  ),

                  SettingCard(
                    icon: Icons.mic,
                    title: "Audio",
                    value: controller.audioText,
                    onTap: () {
                      showModalBottomSheet(
                        context: context,
                        builder: (context) {
                          return SafeArea(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  leading: const Icon(Icons.mic),
                                  title: const Text("Microphone"),
                                  onTap: () {
                                    controller.setAudioMode(
                                      AudioMode.microphone,
                                    );
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  leading: const Icon(Icons.speaker),
                                  title: const Text("Internal Audio"),
                                  onTap: () {
                                    controller.setAudioMode(AudioMode.internal);
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  leading: const Icon(Icons.graphic_eq),
                                  title: const Text("Microphone + Internal"),
                                  onTap: () {
                                    controller.setAudioMode(AudioMode.both);
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  leading: const Icon(Icons.volume_off),
                                  title: const Text("Off"),
                                  onTap: () {
                                    controller.setAudioMode(AudioMode.off);
                                    Navigator.pop(context);
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      );
                    },
                  ),

                  SettingCard(
                    icon: Icons.layers,
                    title: "Floating Panel",
                    value: controller.floatingPanelText,
                    onTap: () {
                      showModalBottomSheet(
                        context: context,
                        builder: (context) {
                          return SafeArea(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  title: const Text("Off"),
                                  onTap: () {
                                    controller.setFloatingPanel(
                                      FloatingPanelMode.off,
                                    );
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("Compact"),
                                  onTap: () {
                                    controller.setFloatingPanel(
                                      FloatingPanelMode.compact,
                                    );
                                    Navigator.pop(context);
                                  },
                                ),
                                ListTile(
                                  title: const Text("Expanded"),
                                  onTap: () {
                                    controller.setFloatingPanel(
                                      FloatingPanelMode.expanded,
                                    );
                                    Navigator.pop(context);
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      );
                    },
                  ),
                ],
              ),
            ),
          ),
          if (controller.isRecording &&
              controller.settings.floatingPanelMode ==
                  FloatingPanelMode.expanded)
            FloatingOverlay(
              child: FloatingPanel(
                isPaused: controller.isPaused,
                onPause: controller.pauseRecording,
                onResume: controller.resumeRecording,
                onStop: controller.stopRecording,
              ),
            ),
          // Compact mode
          if (controller.isRecording &&
              controller.settings.floatingPanelMode ==
                  FloatingPanelMode.compact)
            FloatingOverlay(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Panel faqat bubble bosilganda chiqadi
                  if (showFloatingPanel)
                    FloatingPanel(
                      isPaused: controller.isPaused,
                      onPause: controller.pauseRecording,
                      onResume: controller.resumeRecording,
                      onStop: () {
                        controller.stopRecording();
                        setState(() {
                          showFloatingPanel = false;
                        });
                      },
                    ),

                  // Bubble doim ko'rinadi
                  FloatingBubble(
                    onTap: () {
                      setState(() {
                        showFloatingPanel = !showFloatingPanel;
                      });
                    },
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
