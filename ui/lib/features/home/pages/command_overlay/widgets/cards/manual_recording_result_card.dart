import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/theme/theme_context.dart';

class ManualRecordingResultCard extends StatelessWidget {
  const ManualRecordingResultCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final success = cardData['success'] == true;
    final recordingSuccess = cardData['recordingSuccess'] == true ||
        cardData['recording_success'] == true ||
        success;
    final conversionSuccess = cardData['conversionSuccess'] == true ||
        cardData['conversion_success'] == true;
    final runId = (cardData['runId'] ?? cardData['run_id'] ?? '')
        .toString()
        .trim();
    final actionCount = _asInt(
      cardData['actionCount'] ?? cardData['action_count'],
    );
    final functionId = (cardData['functionId'] ?? cardData['function_id'] ?? '')
        .toString()
        .trim();
    final error = (cardData['errorMessage'] ?? cardData['error_message'] ?? '')
        .toString()
        .trim();
    final statusColor = recordingSuccess
        ? const Color(0xFF5B21B6)
        : const Color(0xFFE05243);
    final backgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            statusColor.withValues(alpha: 0.10),
            palette.surfaceSecondary,
          )
        : statusColor.withValues(alpha: 0.08);

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.86,
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          clipBehavior: Clip.antiAlias,
          child: Ink(
            decoration: BoxDecoration(
              color: backgroundColor,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: InkWell(
              onTap: runId.isEmpty
                  ? null
                  : () {
                      unawaited(
                        showRunLogTimelineSheet(
                          context,
                          runId: runId,
                          title: '手动录制 RunLog',
                        ),
                      );
                    },
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        Icon(
                          recordingSuccess
                              ? Icons.gesture_rounded
                              : Icons.error_outline_rounded,
                          size: 18,
                          color: statusColor,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            recordingSuccess
                                ? (conversionSuccess ? '手动录制完成' : '手动录制已保存')
                                : '手动录制失败',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: palette.textPrimary,
                              height: 1.25,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        _Tag(label: 'steps', value: actionCount.toString()),
                        if (runId.isNotEmpty)
                          _Tag(label: 'runlog', value: runId),
                        if (functionId.isNotEmpty)
                          _Tag(label: '复用指令', value: functionId),
                      ],
                    ),
                    if (error.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(
                        error,
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textSecondary,
                          height: 1.35,
                        ),
                      ),
                    ],
                    if (recordingSuccess && runId.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(
                        conversionSuccess
                            ? '点击查看完整 RunLog 和可编辑步骤'
                            : '点击查看 RunLog；复用指令可稍后重新生成',
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textSecondary,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: palette.surfacePrimary.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        '$label $value',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: palette.textSecondary,
          height: 1.2,
        ),
      ),
    );
  }
}

int _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.round();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}
