import 'package:flutter/material.dart';

IconData homeQuickPromptIcon(String iconKey) {
  return switch (iconKey) {
    'summarize' => Icons.notes_rounded,
    'plan' => Icons.route_outlined,
    'execute' => Icons.play_arrow_rounded,
    'explore' => Icons.travel_explore_rounded,
    'search' => Icons.manage_search_rounded,
    'install' => Icons.extension_rounded,
    _ => Icons.auto_awesome_rounded,
  };
}
