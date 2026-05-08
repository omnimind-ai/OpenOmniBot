import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';

class ProfileSection extends StatelessWidget {
  final String username;
  final String avatarUrl;
  final VoidCallback? onTap;
  
  const ProfileSection({
    required this.username,
    required this.avatarUrl,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final label = Localizations.localeOf(context).languageCode == 'en'
        ? 'Edit profile'
        : '编辑个人资料';
    return Semantics(
      button: onTap != null,
      enabled: onTap != null,
      label: label,
      child: GestureDetector(
        onTap: onTap,
        child: Column(
          children: [
            Stack(
              children: [
                Container(
                  width: 100,
                  height: 100,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white, width: 4),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.05),
                        blurRadius: 10,
                        spreadRadius: 0,
                        offset: const Offset(3, 4),
                      ),
                    ],
                  ),
                  child: ClipOval(
                    child: Image.asset(
                      avatarUrl,
                      fit: BoxFit.cover,
                    ),
                  ),
                ),
                Positioned(
                  right: 0,
                  bottom: 0,
                  child: ExcludeSemantics(
                    child: Container(
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        color: Colors.white,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white, width: 3),
                      ),
                      child: Center(
                        child: SvgPicture.asset(
                          'assets/my/edit-1.svg',
                          width: 16,
                          height: 16,
                          color: AppColors.primaryBlue,
                          errorBuilder: (context, error, stackTrace) =>
                            const Icon(Icons.edit, size: 1, color: Colors.white),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text(
              'Hi, ' + username,
              style: TextStyle(
                fontSize: AppTextStyles.fontSizeH2,
                fontWeight: AppTextStyles.fontWeightMedium,
                height: AppTextStyles.lineHeightH1,
                letterSpacing: AppTextStyles.letterSpacingWide,
                color: AppColors.text90,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
