import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class NormalOptionsCard extends StatefulWidget {
  final String title;
  final String subtitle;
  final List<OptionItem> options;
  final Function(List<OptionItem>)? onSelectionChanged;
  final bool multiSelect;

  const NormalOptionsCard({
    Key? key,
    required this.title,
    required this.subtitle,
    required this.options,
    this.onSelectionChanged,
    this.multiSelect = true,
  }) : super(key: key);

  @override
  State<NormalOptionsCard> createState() => _NormalOptionsCardState();
}

class _NormalOptionsCardState extends State<NormalOptionsCard> {
  List<OptionItem> _selectedItems = [];
  bool _allSelected = false;

  @override
  void initState() {
    super.initState();
    // 初始化选中项
    _selectedItems = widget.options.where((item) => item.isSelected).toList();
  }

  void _toggleItem(OptionItem item) {
    setState(() {
      if (widget.multiSelect) {
        // 多选逻辑
        if (_selectedItems.contains(item)) {
          _selectedItems.remove(item);
        } else {
          _selectedItems.add(item);
        }
      } else {
        // 单选逻辑
        _selectedItems = [item]; // 只保留当前选中的项
      }
      _updateAllSelectedState();
    });
    widget.onSelectionChanged?.call(_selectedItems);
  }

  void _toggleAll() {
    setState(() {
      if (_allSelected) {
        _selectedItems = [];
      } else {
        _selectedItems = List.from(widget.options);
      }
      _allSelected = !_allSelected;
    });
    widget.onSelectionChanged?.call(_selectedItems);
  }

  void _updateAllSelectedState() {
    _allSelected = _selectedItems.length == widget.options.length;
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 头部信息和全选
              Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      (LegacyTextLocalizer.isEnglish
                          ? '${widget.options.length} charge items'
                          : '有${widget.options.length}个扣费项'),
                      style: TextStyle(
                        color: Colors.grey.shade700,
                        fontSize: 12,
                      ),
                    ),
                    if (widget.multiSelect)
                      GestureDetector(
                        onTap: _toggleAll,
                        child: Text(
                          _allSelected
                              ? (LegacyTextLocalizer.isEnglish ? 'Deselect all' : '取消全选')
                              : (LegacyTextLocalizer.isEnglish ? 'Select all' : '全选'),
                          style: TextStyle(
                            color: Theme.of(context).primaryColor,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                  ],
                ),
              ),

              // 分隔线
              Divider(height: 1, color: Colors.grey.shade300),

              // 选项列表
              ListView.separated(
                physics: const NeverScrollableScrollPhysics(),
                shrinkWrap: true,
                itemCount: widget.options.length,
                padding: EdgeInsets.zero,
                separatorBuilder: (context, index) => Divider(
                  height: 1,
                  color: Colors.grey.shade300,
                ),
                itemBuilder: (context, index) {
                  final item = widget.options[index];
                  final isSelected = _selectedItems.contains(item);
                  
                  return ListTile(
                    onTap: () => _toggleItem(item),
                    minTileHeight: 40,
                    minLeadingWidth: 10,
                    leading: item.icon != null 
                        ? Container(
                            width: 18,
                            height: 18,
                            decoration: BoxDecoration(
                              color: Colors.lightBlueAccent,
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Center(child: item.icon),
                          )
                        : null,
                    title: Text(
                      item.title,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    trailing: Container(
                      width: 18,
                      height: 18,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: isSelected ? null : Border.all(color: Colors.grey.shade400, width: 1.5),
                      ),
                      child: isSelected 
                          ? const Icon(
                              Icons.check_circle,
                              size: 18,
                              color: Colors.black87,
                            )
                          : null,
                    ),
                  );
                },
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class OptionItem {
  final String title;
  final Widget? icon;
  final bool isSelected;
  final dynamic value;

  const OptionItem({
    required this.title,
    this.icon,
    this.isSelected = false,
    this.value,
  });

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is OptionItem &&
        other.title == title &&
        other.value == value;
  }

  @override
  int get hashCode => title.hashCode ^ value.hashCode;
}

// 使用示例
class NormalOptionsCardExample extends StatelessWidget {
  const NormalOptionsCardExample({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return NormalOptionsCard(
      title: LegacyTextLocalizer.isEnglish ? 'Auto-renewal / Password-free payment' : '自动续费/免密支付',
      subtitle: LegacyTextLocalizer.isEnglish ? 'These auto-charge items exist, which ones do you want to disable?' : '目前有这些自动扣费项目，你想关闭哪些？',
      options: [
        OptionItem(
          title: LegacyTextLocalizer.isEnglish ? 'Ninebot auto-renewal' : '九号自动续费',
          icon: const Text('9', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
          isSelected: true,
        ),
        OptionItem(
          title: LegacyTextLocalizer.isEnglish ? 'Apple services' : 'Apple服务',
          icon: const Icon(Icons.apple, color: Colors.black, size: 18),
        ),
        OptionItem(
          title: LegacyTextLocalizer.isEnglish ? 'Credit borrowing service' : '信用借还服务商',
          icon: const Icon(Icons.credit_card, color: Colors.green, size: 18),
        ),
        OptionItem(
          title: LegacyTextLocalizer.isEnglish ? '88VIP membership auto-renewal' : '88VIP会员自动续费服务',
          icon: const Icon(Icons.card_membership, color: Colors.orange, size: 18),
        ),
        OptionItem(
          title: LegacyTextLocalizer.isEnglish ? 'Meituan charge password-free' : '美团充电免密支付',
          icon: const Icon(Icons.delivery_dining, color: Colors.amber, size: 18),
        ),
      ],
    );
  }
}
