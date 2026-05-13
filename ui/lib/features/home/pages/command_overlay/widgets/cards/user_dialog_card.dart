import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/widgets/user_dialog_registry.dart';
import 'package:ui/models/agent_stream_event.dart';

/// Inline card rendered for `user_dialog` clarify events.
///
/// Supports three types:
///   confirm — two buttons (confirm / cancel)
///   choices — selectable option list
///   input   — single-line text input + submit
///
/// Tapping any action calls [UserDialogRegistry.submit] with the response text,
/// which the active chat page translates into a new user message.
class UserDialogCard extends StatefulWidget {
  const UserDialogCard({super.key, required this.dialog});

  final UserDialog dialog;

  static UserDialogCard? tryFromCardData(Map<String, dynamic> cardData) {
    final raw = cardData['dialogData'];
    if (raw == null) return null;
    final dialog = UserDialog.tryParse(raw);
    if (dialog == null) return null;
    return UserDialogCard(dialog: dialog);
  }

  @override
  State<UserDialogCard> createState() => _UserDialogCardState();
}

class _UserDialogCardState extends State<UserDialogCard> {
  bool _submitted = false;
  String? _selectedValue;
  final TextEditingController _inputController = TextEditingController();

  @override
  void dispose() {
    _inputController.dispose();
    super.dispose();
  }

  void _submit(String value) {
    if (_submitted) return;
    setState(() {
      _submitted = true;
      _selectedValue = value;
    });
    UserDialogRegistry.submit(value);
  }

  @override
  Widget build(BuildContext context) {
    return switch (widget.dialog.type) {
      'confirm' => _buildConfirm(context),
      'choices' => _buildChoices(context),
      'input'   => _buildInput(context),
      _         => const SizedBox.shrink(),
    };
  }

  // ── confirm ──────────────────────────────────────────────────────────────

  Widget _buildConfirm(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final confirmLabel = widget.dialog.confirmLabel ?? '确定';
    final cancelLabel  = widget.dialog.cancelLabel  ?? '取消';
    final isDanger     = widget.dialog.danger;

    return _CardShell(
      title: widget.dialog.title,
      message: widget.dialog.message,
      submitted: _submitted,
      child: Row(
        children: [
          Expanded(
            child: _OutlineBtn(
              label: cancelLabel,
              onTap: _submitted ? null : () => _submit('cancelled'),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: _FilledBtn(
              label: confirmLabel,
              color: isDanger ? const Color(0xFFDC2626) : cs.primary,
              onTap: _submitted ? null : () => _submit('confirmed'),
            ),
          ),
        ],
      ),
    );
  }

  // ── choices ──────────────────────────────────────────────────────────────

  Widget _buildChoices(BuildContext context) {
    return _CardShell(
      title: widget.dialog.title,
      message: widget.dialog.message,
      submitted: _submitted,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: widget.dialog.choices.map((choice) {
          final selected = _submitted && _selectedValue == choice.value;
          return Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _ChoiceOption(
              label: choice.label,
              hint: choice.hint,
              selected: selected,
              disabled: _submitted,
              onTap: _submitted ? null : () => _submit(choice.value),
            ),
          );
        }).toList(),
      ),
    );
  }

  // ── input ────────────────────────────────────────────────────────────────

  Widget _buildInput(BuildContext context) {
    final inputType = switch (widget.dialog.inputType) {
      'number' => TextInputType.numberWithOptions(decimal: true),
      'date'   => TextInputType.datetime,
      _        => TextInputType.text,
    };

    return _CardShell(
      title: widget.dialog.title,
      message: widget.dialog.message,
      submitted: _submitted,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!_submitted)
            TextField(
              controller: _inputController,
              keyboardType: inputType,
              decoration: InputDecoration(
                hintText: widget.dialog.placeholder ?? '',
                hintStyle: const TextStyle(color: Color(0xFF999999)),
                filled: true,
                fillColor: const Color(0xFFF5F5F7),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 14, vertical: 12,
                ),
              ),
              style: const TextStyle(fontSize: 15),
              onSubmitted: (v) {
                final text = v.trim();
                if (text.isNotEmpty) _submit(text);
              },
            ),
          if (_submitted)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: const Color(0xFFF5F5F7),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                _selectedValue ?? '',
                style: const TextStyle(fontSize: 15),
              ),
            ),
          const SizedBox(height: 10),
          _FilledBtn(
            label: widget.dialog.confirmLabel ?? '提交',
            onTap: _submitted
                ? null
                : () {
                    final text = _inputController.text.trim();
                    if (text.isNotEmpty) _submit(text);
                  },
          ),
        ],
      ),
    );
  }
}

// ── Sub-widgets ──────────────────────────────────────────────────────────────

class _CardShell extends StatelessWidget {
  const _CardShell({
    required this.message,
    required this.submitted,
    required this.child,
    this.title,
  });

  final String? title;
  final String message;
  final bool submitted;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: submitted ? 0.6 : 1.0,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(14),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withAlpha(12),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (title != null && title!.isNotEmpty) ...[
              Text(
                title!,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFF0D1117),
                ),
              ),
              const SizedBox(height: 6),
            ],
            Text(
              message,
              style: const TextStyle(
                fontSize: 14,
                color: Color(0xFF555555),
                height: 1.5,
              ),
            ),
            const SizedBox(height: 14),
            child,
          ],
        ),
      ),
    );
  }
}

class _FilledBtn extends StatelessWidget {
  const _FilledBtn({
    required this.label,
    required this.onTap,
    this.color,
  });

  final String label;
  final VoidCallback? onTap;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final bg = color ?? Theme.of(context).colorScheme.primary;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 44,
        decoration: BoxDecoration(
          color: onTap == null ? bg.withAlpha(100) : bg,
          borderRadius: BorderRadius.circular(10),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}

class _OutlineBtn extends StatelessWidget {
  const _OutlineBtn({required this.label, required this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 44,
        decoration: BoxDecoration(
          border: Border.all(color: const Color(0xFFDDDDDD)),
          borderRadius: BorderRadius.circular(10),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w500,
            color: onTap == null
                ? const Color(0xFFAAAAAA)
                : const Color(0xFF333333),
          ),
        ),
      ),
    );
  }
}

class _ChoiceOption extends StatelessWidget {
  const _ChoiceOption({
    required this.label,
    required this.selected,
    required this.disabled,
    required this.onTap,
    this.hint,
  });

  final String label;
  final String? hint;
  final bool selected;
  final bool disabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final primary = Theme.of(context).colorScheme.primary;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: selected
              ? primary.withAlpha(20)
              : const Color(0xFFF7F8FC),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: selected ? primary : const Color(0xFFE0E0E0),
            width: selected ? 1.5 : 1.0,
          ),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight:
                          selected ? FontWeight.w600 : FontWeight.w500,
                      color: selected ? primary : const Color(0xFF222222),
                    ),
                  ),
                  if (hint != null && hint!.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      hint!,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Color(0xFF888888),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (selected)
              Icon(Icons.check_circle_rounded, size: 18, color: primary),
          ],
        ),
      ),
    );
  }
}
