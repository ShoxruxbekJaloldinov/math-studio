import 'package:flutter/material.dart';
import 'home_page.dart';
import 'package:provider/provider.dart';
import 'controllers/recorder_controller.dart';

void main() {
  runApp(
    ChangeNotifierProvider(
      create: (_) => RecorderController(),
      child: const RecorderApp(),
    ),
  );
}

class RecorderApp extends StatelessWidget {
  const RecorderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Screen Recorder',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.blue),
      home: HomePage(),
    );
  }
}
