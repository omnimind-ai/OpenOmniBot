import 'package:flutter/material.dart';
import '../l10n/legacy_text_localizer.dart';

class NormalChoicesGroup extends StatefulWidget {
  final List<ChoiceOption> options;
  final List<ChoiceOption>? selectedOptions;
  final Function(List<ChoiceOption>)? onSelectionChanged;
  final bool multiSelect;

  const NormalChoicesGroup({
    Key? key,
    required this.options,
    this.selectedOptions,
    this.onSelectionChanged,
    this.multiSelect = false,
  }) : super(key: key);

  @override
  State<NormalChoicesGroup> createState() => _NormalChoicesGroupState();
}

class _NormalChoicesGroupState extends State<NormalChoicesGroup> {
  List<ChoiceOption> _selectedOptions = [];

  @override
  void initState() {
    super.initState();
    _selectedOptions = widget.selectedOptions ?? [];
  }

  void _toggleOption(ChoiceOption option) {
    setState(() {
      if (widget.multiSelect) {
        // 多选模式
        if (_selectedOptions.contains(option)) {
          _selectedOptions.remove(option);
        } else {
          _selectedOptions.add(option);
        }
      } else {
        // 单选模式
        if (_selectedOptions.contains(option)) {
          _selectedOptions.clear(); // 可选：点击已选中项取消选择
        } else {
          _selectedOptions = [option]; // 替换为当前选项
        }
      }
    });
    widget.onSelectionChanged?.call(_selectedOptions);
  }

  @override
  Widget build(BuildContext context) {
    // 选项按钮组
    return Container(
      width: double.infinity,
      child: Wrap(
        spacing: 8.0,
        runSpacing: 8.0,
        children: widget.options.map((option) {
          final isSelected = _selectedOptions.contains(option);
          return GestureDetector(
            onTap: () => _toggleOption(option),
            child: Container(
              padding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 8,
              ),
              decoration: BoxDecoration(
                color: isSelected 
                    ?  Colors.grey
                    : Colors.grey.shade100,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (option.icon != null) ...[
                    Container(
                      width: 20,
                      height: 20,
                      decoration: BoxDecoration(
                        color: option.iconBackgroundColor,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Center(
                        child: option.icon,
                      ),
                    ),
                    const SizedBox(width: 6),
                  ],
                  Text(
                    option.label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: isSelected 
                          ? Colors.white 
                          : Colors.black87,
                    ),
                  ),
                ],
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

class ChoiceOption {
  final String label;
  final Icon? icon;
  final Color? iconBackgroundColor;
  final dynamic value;

  const ChoiceOption({
    required this.label,
    this.icon,
    this.iconBackgroundColor,
    this.value,
  });

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ChoiceOption &&
        other.label == label &&
        other.value == value;
  }

  @override
  int get hashCode => label.hashCode ^ value.hashCode;
}

// 使用示例
class NormalChoicesGroupExample extends StatelessWidget {
  const NormalChoicesGroupExample({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return NormalChoicesGroup(
      options: [
        ChoiceOption(
          label: LegacyTextLocalizer.isEnglish ? 'Alipay' : '支付宝',
          icon: const Icon(
            Icons.account_balance_wallet,
            color: Colors.white,
            size: 14,
          ),
          iconBackgroundColor: Colors.blue,
          value: 'alipay',
        ),
        ChoiceOption(
          label: LegacyTextLocalizer.isEnglish ? 'WeChat' : '微信',
          icon: const Icon(
            Icons.chat,
            color: Colors.white,
            size: 14,
          ),
          iconBackgroundColor: Colors.green,
          value: 'wechat',
        ),
      ],
      onSelectionChanged: (selectedOptions) {
        print('Selected: ${selectedOptions.map((e) => e.label).toList()}');
      },
    );
  }
}
