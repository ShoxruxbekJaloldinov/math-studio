import 'package:flutter/material.dart';

class RecordButton extends StatelessWidget {
  final bool isRecording;
  final VoidCallback onTap;

  const RecordButton({
    super.key,
    required this.isRecording,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 140,
        height: 140,
        decoration: BoxDecoration(
          color: isRecording ? Colors.green : Colors.red,
          shape: BoxShape.circle,
          boxShadow: [
            BoxShadow(
              color: Colors.red.withValues(alpha: 0.5),
              blurRadius: 25,
              spreadRadius: 5,
            ),
          ],
        ),
        child: Icon(
          isRecording ? Icons.stop : Icons.fiber_manual_record,
          color: Colors.white,
          size: 70,
        ),
      ),
    );
  }
}
