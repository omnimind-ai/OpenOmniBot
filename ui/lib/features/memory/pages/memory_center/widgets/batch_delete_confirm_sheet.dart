import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/app_colors.dart';

/// 批量删除确认弹窗
/// 返回true表示确认删除，false表示取消删除
class BatchDeleteConfirmSheet extends StatelessWidget {
  final int count;

  /// 单位名称，默认为"记忆"，可自定义如"记录"
  final String unit;

  const BatchDeleteConfirmSheet({
    Key? key,
    required this.count,
    this.unit = '记忆',
  }) : super(key: key);
  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(8),
          topRight: Radius.circular(8),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.15),
            blurRadius: 4,
            offset: Offset(0, 0),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 36, 24, 40),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                context.l10n.memoryDeleteConfirmTitle,
                style: TextStyle(
                  color: AppColors.text,
                  fontSize: 16,
                  fontWeight: FontWeight.w500,
                  height: 1.5,
                ),
              ),
              SizedBox(height: 12),
              Text(
                context.l10n.memoryDeleteWarning,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: AppColors.text70,
                  fontSize: 14,
                  fontWeight: FontWeight.w400,
                  height: 1.5,
                  letterSpacing: 0.39,
                ),
              ),
              SizedBox(height: 50),
              Row(
                children: [
                  // 取消按钮
                  Expanded(
                    child: GestureDetector(
                      onTap: () => Navigator.of(context).pop(false),
                      child: Container(
                        height: 34,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(4),
                          border: Border.all(
                            color: AppColors.buttonPrimary,
                            width: 1,
                          ),
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          context.trLegacy('取消'),
                          style: TextStyle(
                            color: AppColors.buttonPrimary,
                            fontSize: 12,
                            fontWeight: FontWeight.w400,
                            height: 1.5,
                          ),
                        ),
                      ),
                    ),
                  ),
                  SizedBox(width: 15),
                  // 删除按钮
                  Expanded(
                    child: GestureDetector(
                      onTap: () => Navigator.of(context).pop(true),
                      child: Container(
                        height: 34,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(4),
                          border: Border.all(
                            color: AppColors.alertRed,
                            width: 1,
                          ),
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          context.l10n.skillDelete,
                          style: TextStyle(
                            color: AppColors.alertRed,
                            fontSize: 12,
                            fontWeight: FontWeight.w400,
                            height: 1.5,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
