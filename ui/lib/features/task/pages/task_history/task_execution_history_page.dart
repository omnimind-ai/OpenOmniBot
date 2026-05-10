import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'widgets/history_card.dart';
import 'package:intl/intl.dart';
import 'package:ui/models/task_models.dart';
import 'package:ui/widgets/common_app_bar.dart';

class TaskExecutionHistoryPage extends StatelessWidget {
  const TaskExecutionHistoryPage({super.key});

  @override
  Widget build(BuildContext context) {
    final List<TaskHistorySection> sections = [];
    return Scaffold(
      backgroundColor: Colors.grey.shade100,
      appBar: CommonAppBar(title: context.l10n.executionHistoryTitle, primary: true),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
            child: Column(
              children: [
                Text(
                  context.l10n.executionHistorySubtitle,
                  style: TextStyle(
                    fontSize: 16,
                    color: Colors.black87,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: sections.isEmpty
                ? Center(
                    child: Text(
                      context.l10n.executionHistoryEmpty,
                      style: TextStyle(fontSize: 14, color: Colors.black54),
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    itemCount: sections.length,
                    itemBuilder: (context, index) {
                      final section = sections[index];
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 20),
                        child: _HistorySection(section: section),
                      );
                    },
                  ),
          ),
        ],
      )

    );
  }
}

class _HistorySection extends StatelessWidget {
  final TaskHistorySection section;
  const _HistorySection({required this.section});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              DateFormat('MM-dd').format(section.dateLabel),
              style: const TextStyle(
                fontSize: 15,
                color: Colors.black87,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.grey.shade200,
                borderRadius: BorderRadius.circular(5),
              ),
              child: Text(
                context.l10n.executionHistoryTaskLabel(section.repeatOption.label),
                style: const TextStyle(fontSize: 10, color: Colors.black54),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Column(
          children: section.records
              .map((record) => HistoryCard(record: record))
              .toList(),
        ),
      ],
    );
  }
}
