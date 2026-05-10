import 'package:flutter/material.dart';
import '../models/block_models.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:url_launcher/url_launcher.dart';
class ReferenceLabel extends StatelessWidget {
  final List<ReferenceItem> referenceItems;

  const ReferenceLabel({
    super.key,
    required this.referenceItems,
  });

  void _showReferenceList(BuildContext context, List<ReferenceItem> items) {
    showModalBottomSheet(
      context: context,
      builder: (BuildContext context) {
        return DraggableScrollableSheet(
          expand: false,
          builder: (context, scrollController) {
            return Container(
              padding: const EdgeInsets.all(16.0),
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.vertical(
                  top: Radius.circular(16.0),
                ),
              ),
              child: ListView.builder(
                controller: scrollController,
                itemCount: items.length,
                itemBuilder: (context, index) {
                  return ListTile(
                    title: Text(LegacyTextLocalizer.isEnglish ? 'Reference ${index + 1}' : '参考文档 ${index + 1}'),
                    subtitle: Text(items[index].title),
                    onTap: () {
                      Navigator.pop(context);
                      // 前往item的链接
                      launchUrl(Uri.parse(items[index].url));
                      // Handle document tap
                    },
                  );
                },
              ),
            );
          },
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    const backGroundColor = Color(0xFFF8F8F8);
    const lightGrey = Color(0xFF999999);
    return GestureDetector(
      onTap: () => _showReferenceList(context,referenceItems),
      child: Container(
        padding: const EdgeInsets.symmetric(
          horizontal: 4.0,
          vertical: 2.0,
        ),
        decoration: BoxDecoration(
          color: backGroundColor,
          borderRadius: BorderRadius.circular(8.0),
          border: Border.all(
            color: lightGrey,
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              (LegacyTextLocalizer.isEnglish
                  ? 'Found ${referenceItems.length} related documents'
                  : "已找到${referenceItems.length}篇相关文档"),
              style: TextStyle(
                color: lightGrey,
                fontWeight: FontWeight.w500,
                fontSize: 12,
              ),
            ),
            Icon(
              Icons.call_made,
              size: 16,
              color: lightGrey,
            ),
          ],
        ),
      ),
    );
  }
}
