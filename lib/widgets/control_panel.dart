import 'package:flutter/material.dart';

class ControlPanel extends StatelessWidget {
  final bool isPaused;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onStop;

  const ControlPanel({
    super.key,
    required this.isPaused,
    required this.onPause,
    required this.onResume,
    required this.onStop,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 25),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.08),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          ElevatedButton.icon(
            onPressed: isPaused ? onResume : onPause,
            icon: Icon(isPaused ? Icons.play_arrow : Icons.pause),
            label: Text(isPaused ? "Resume" : "Pause"),
          ),
          const SizedBox(width: 15),
          ElevatedButton.icon(
            onPressed: onStop,
            icon: const Icon(Icons.stop),
            label: const Text("Stop"),
          ),
        ],
      ),
    );
  }
}