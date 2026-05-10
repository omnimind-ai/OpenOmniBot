import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_chip.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';

class RecordListItem extends StatelessWidget {
  final RecordListItemData recordModel;
  final void Function(BuildContext context, Offset position)? onMorePressed;
  final void Function(int recordId, bool targetStatus)? onRecommendPressed;

  const RecordListItem({
    Key? key,
    required this.recordModel,
    this.onMorePressed,
    this.onRecommendPressed,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Expanded(
              child: Text(
                recordModel.title,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.70,
                  letterSpacing: 0.50,
                  color: Color(0xFF333333),
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            // GestureDetector(
                        // child: SizedBox(
                      //   width: 17,
                      //   height: 3,
                      //   child: SvgPicture.asset(
                      //     'assets/common/more.svg',
                      //     width: 17,
                      //     height: 3,
                      //     colorFilter: const ColorFilter.mode(
                      //       Color(0xFF1A1A1A), // icon_nav_secondary
                      //       BlendMode.srcIn,
                      //     ),
                      //   ),
                      // ),
            //   onTapDown: (details) => onMorePressed?.call(context, details.globalPosition),
            // ),
          ],
        ),
        const SizedBox(height: 8),
        recordModel.timeLabel != null
          ? Text(
            recordModel.timeLabel!,
            style: const TextStyle(
              fontSize: 12,
              color: Color(0x80000000),
              height: 1.50,
            ),
          )
          : const SizedBox(height: 18),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
          // 渲染tags中的数据
          if (recordModel.tags.isNotEmpty)
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: recordModel.tags.map((tag) {
                return TagChip(
                  title: tag.label,
                  iconPath: tag.icon ?? Icons.label,
                  appIconProvider: tag.appIconProvider,
                );
              }).toList(),
            ),
          if (recordModel.showRecommended)
            GestureDetector(
              onTap: () {
                onRecommendPressed?.call(recordModel.id, !recordModel.isRecommended);
              },
              child: Row(
                children: [
                  SvgPicture.asset(
                    'assets/memory/favorite_icon.svg',
                    width: 15,
                    height: 13,
                    colorFilter: ColorFilter.mode(
                      recordModel.isRecommended
                          ? AppColors.primaryBlue
                          : const Color(0x33000000),
                      BlendMode.srcIn,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Text(
                    LegacyTextLocalizer.isEnglish ? 'Recommend' : '优先推荐',
                    style: TextStyle(
                      fontSize: 12,
                      color: recordModel.isRecommended
                          ? AppColors.primaryBlue
                          : const Color(0x33000000),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class RecordListItemData {
  final int id;
  final String title;
  final String? timeLabel;
  final List<AppTag> tags;
  final bool showRecommended;
  final bool isRecommended;
  final String? section;

  RecordListItemData({
    required this.id,
    required this.title,
    this.timeLabel,
    required this.tags,
    this.showRecommended = false,
    this.isRecommended = false,
    this.section,
  });

  RecordListItemData copyWith({
    int? id,
    String? title,
    String? timeLabel,
    List<AppTag>? tags,
    bool? showRecommended,
    bool? isRecommended,
    String? section,
  }) {
    return RecordListItemData(
      id: id ?? this.id,
      title: title ?? this.title,
      timeLabel: timeLabel ?? this.timeLabel,
      tags: tags ?? this.tags,
      showRecommended: showRecommended ?? this.showRecommended,
      isRecommended: isRecommended ?? this.isRecommended,
      section: section ?? this.section,
    );
  }
}
