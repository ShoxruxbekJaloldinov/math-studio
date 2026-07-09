import 'package:flutter/material.dart';

class FloatingOverlay extends StatefulWidget {
  final Widget child;

  const FloatingOverlay({super.key, required this.child});

  @override
  State<FloatingOverlay> createState() => _FloatingOverlayState();
}

class _FloatingOverlayState extends State<FloatingOverlay> {
  double top = 250;
  double right = 20;

  @override
  Widget build(BuildContext context) {
    return Positioned(
      top: top,
      right: right,
      child: GestureDetector(
        onPanUpdate: (details) {
          setState(() {
            top += details.delta.dy;
            right -= details.delta.dx;
          });
        },
        child: widget.child,
      ),
    );
  }
}
