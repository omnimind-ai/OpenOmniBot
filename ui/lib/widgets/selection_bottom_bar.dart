import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';
  

  class SelectionBottomBar extends StatelessWidget {
    final bool isActive;
    final VoidCallback? onDeletePressed;

    const SelectionBottomBar({
      Key? key,
      required this.isActive,
      this.onDeletePressed,
    }) : super(key: key);

    @override
    Widget build(BuildContext context) {
      if (!isActive) {
        return SizedBox.shrink();
      }
      return _buildSelectionBottomBar();
    }
  // 选择模式下的底部删除按钮栏
  Widget _buildSelectionBottomBar() {
    return GestureDetector(
      onTap: onDeletePressed,
      child: Container(
        height: 56,
        width: double.infinity,
        decoration: BoxDecoration(
          color: Color(0xFFFFFFFF),
          border: Border(
            top: BorderSide(color: Colors.black.withOpacity(0.1), width: 0.5),
          ),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SvgPicture.asset(
              'assets/memory/memory_delete.svg',
              width: 20,
              height: 20,
              colorFilter: ColorFilter.mode(
                AppColors.alertRed,
                BlendMode.srcIn,
              ),
            ),
            Text(
              LegacyTextLocalizer.isEnglish ? 'Delete' : '删除',
              style: TextStyle(
                color: AppColors.alertRed,
                fontSize: 12,
                fontWeight: FontWeight.w400,
                height: 1.5,
              ),
            ),
          ],
        ),
      ),
    );
  }
}