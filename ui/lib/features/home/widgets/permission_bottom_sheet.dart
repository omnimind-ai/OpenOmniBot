import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

/// 权限缺失底部弹窗
/// 当用户在首页有权限未授权时展示
class PermissionBottomSheet extends StatefulWidget {
  /// 权限全部授权后的回调
  final VoidCallback? onAllAuthorized;

  /// 预加载的权限列表（必需）
  final List<PermissionData> initialPermissions;

  /// 设备品牌（必需）
  final String deviceBrand;

  /// 按钮文案（可选，默认为"开启陪伴"）
  final String buttonText;

  const PermissionBottomSheet({
    super.key,
    this.onAllAuthorized,
    required this.initialPermissions,
    required this.deviceBrand,
    this.buttonText = '开启陪伴',
  });

  /// 显示权限弹窗
  static Future<void> show(
    BuildContext context, {
    VoidCallback? onAllAuthorized,
    required List<PermissionData> initialPermissions,
    required String deviceBrand,
    String buttonText = '开启陪伴',
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => PermissionBottomSheet(
        onAllAuthorized: onAllAuthorized,
        initialPermissions: initialPermissions,
        deviceBrand: deviceBrand,
        buttonText: buttonText,
      ),
    );
  }

  @override
  State<PermissionBottomSheet> createState() => _PermissionBottomSheetState();
}

class _PermissionBottomSheetState extends State<PermissionBottomSheet>
    with WidgetsBindingObserver {
  final ValueNotifier<bool> allAuthorized = ValueNotifier(false);
  late List<PermissionData> permissions;
  bool _isCompactMode = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // 使用预加载的数据
    permissions = widget.initialPermissions;
    _isCompactMode = permissions.length >= 5;
    allAuthorized.value = PermissionService.checkAllAuthorized(permissions);
  }

  void _checkAllAuthorized() {
    allAuthorized.value = PermissionService.checkAllAuthorized(permissions);
  }

  Future<void> _checkPermissions() async {
    await PermissionService.checkPermissions(permissions);
    _checkAllAuthorized();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      constraints: BoxConstraints(
        maxHeight: MediaQuery.of(context).size.height * 0.75,
      ),
      decoration: BoxDecoration(
        color: isDark ? palette.surfacePrimary : Colors.white,
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(8),
          topRight: Radius.circular(8),
        ),
        border: isDark
            ? Border(top: BorderSide(color: palette.borderSubtle))
            : null,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 40),
          // 标题区域
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Please check the permissions below'
                    : '请检查下列权限',
                style: TextStyle(
                  color: isDark ? null : AppColors.text,
                  fontSize: 20,
                  fontWeight: FontWeight.w600,
                  height: 1.5,
                ),
              ),
            ],
          ),
          const SizedBox(height: 30),
          // 权限列表
          Flexible(
            child: SingleChildScrollView(
              child: PermissionSection(
                spacing: _isCompactMode ? 26 : 40,
                permissions: permissions,
                onPermissionChanged: _checkPermissions,
              ),
            ),
          ),
          SizedBox(height: _isCompactMode ? 26 : 40),
          // 底部按钮
          Center(
            child: ValueListenableBuilder<bool>(
              valueListenable: allAuthorized,
              builder: (context, authorized, child) {
                return GestureDetector(
                  onTap: () {
                    if (authorized) {
                      // 所有权限已授权，关闭弹窗并执行回调
                      Navigator.of(context).pop();
                      widget.onAllAuthorized?.call();
                    }
                  },
                  child: Opacity(
                    opacity: authorized ? 1.0 : 0.5,
                    child: Container(
                      width: double.infinity,
                      constraints: const BoxConstraints(maxWidth: 288),
                      height: 48,
                      decoration: BoxDecoration(
                        gradient: isDark
                            ? LinearGradient(
                                begin: const Alignment(0.14, -1.09),
                                end: const Alignment(1.10, 1.26),
                                colors: [
                                  Color.lerp(
                                    palette.surfaceElevated,
                                    palette.accentPrimary,
                                    0.18,
                                  )!,
                                  Color.lerp(
                                    palette.surfaceSecondary,
                                    palette.accentPrimary,
                                    0.34,
                                  )!,
                                ],
                              )
                            : const LinearGradient(
                                begin: Alignment(0.14, -1.09),
                                end: Alignment(1.10, 1.26),
                                colors: [Color(0xFF1930D9), Color(0xFF2CA5F0)],
                              ),
                        borderRadius: BorderRadius.circular(8),
                        border: isDark
                            ? Border.all(color: palette.borderSubtle)
                            : null,
                      ),
                      child: Center(
                        child: Text(
                          Localizations.localeOf(context).languageCode == 'en' &&
                                  widget.buttonText == '开启陪伴'
                              ? 'Enable Companion'
                              : widget.buttonText,
                          style: TextStyle(
                            color: isDark ? palette.textPrimary : Colors.white,
                            fontSize: 16,
                            fontFamily: 'PingFang SC',
                            fontWeight: FontWeight.w600,
                            height: 1.5,
                          ),
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 40),
        ],
      ),
    );
  }
}
