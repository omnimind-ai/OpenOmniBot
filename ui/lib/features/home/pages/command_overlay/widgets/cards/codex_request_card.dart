import 'package:flutter/material.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/theme/theme_context.dart';

class CodexRequestCard extends StatefulWidget {
  const CodexRequestCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<CodexRequestCard> createState() => _CodexRequestCardState();
}

class _CodexRequestCardState extends State<CodexRequestCard> {
  final TextEditingController _answerController = TextEditingController();
  bool _isSubmitting = false;
  String? _localStatus;

  @override
  void dispose() {
    _answerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final kind = (widget.cardData['requestKind'] ?? '').toString();
    final title = (widget.cardData['title'] ?? 'Codex request').toString();
    final detail = (widget.cardData['detail'] ?? '').toString();
    final status =
        _localStatus ?? (widget.cardData['status'] ?? 'pending').toString();
    final isPending = status == 'pending' && !_isSubmitting;

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.82,
        ),
        child: Container(
          margin: const EdgeInsets.only(top: 8, bottom: 4),
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          decoration: BoxDecoration(
            color: context.isDarkTheme
                ? palette.surfaceSecondary
                : const Color(0xFFF3F5F6),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: context.isDarkTheme
                  ? palette.borderSubtle
                  : const Color(0xFFE1E5E8),
            ),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: palette.textPrimary,
                  height: 1.2,
                ),
              ),
              if (detail.trim().isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  detail,
                  maxLines: 5,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textSecondary,
                    height: 1.35,
                  ),
                ),
              ],
              const SizedBox(height: 10),
              if (kind == 'user_input' && status == 'pending') ...[
                TextField(
                  controller: _answerController,
                  minLines: 1,
                  maxLines: 3,
                  style: TextStyle(fontSize: 12, color: palette.textPrimary),
                  decoration: InputDecoration(
                    isDense: true,
                    hintText: 'Answer',
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 8,
                    ),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(6),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
              ],
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (_isSubmitting)
                    SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.accentPrimary,
                      ),
                    )
                  else if (status != 'pending')
                    Text(
                      status,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: palette.textSecondary,
                      ),
                    )
                  else if (kind == 'approval') ...[
                    TextButton(
                      onPressed: isPending
                          ? () => _respondApproval(true)
                          : null,
                      child: const Text('Accept'),
                    ),
                    const SizedBox(width: 6),
                    TextButton(
                      onPressed: isPending
                          ? () => _respondApproval(false)
                          : null,
                      child: const Text('Decline'),
                    ),
                  ] else ...[
                    TextButton(
                      onPressed: isPending ? _respondUserInput : null,
                      child: const Text('Submit'),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _respondApproval(bool accepted) async {
    final requestId = widget.cardData['requestId'];
    if (requestId == null) return;
    await _submit(() {
      return CodexAppServerService.respondToApproval(
        requestId: requestId,
        accepted: accepted,
      );
    }, accepted ? 'accepted' : 'declined');
  }

  Future<void> _respondUserInput() async {
    final requestId = widget.cardData['requestId'];
    final questionId = (widget.cardData['questionId'] ?? 'answer').toString();
    if (requestId == null) return;
    final answer = _answerController.text.trim();
    await _submit(() {
      return CodexAppServerService.respondToUserInput(
        requestId: requestId,
        questionId: questionId,
        answers: <String>[answer],
      );
    }, 'submitted');
  }

  Future<void> _submit(
    Future<Map<String, dynamic>> Function() action,
    String successStatus,
  ) async {
    if (_isSubmitting) return;
    setState(() {
      _isSubmitting = true;
    });
    try {
      await action();
      if (!mounted) return;
      setState(() {
        _localStatus = successStatus;
        _isSubmitting = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _localStatus = 'failed';
        _isSubmitting = false;
      });
    }
  }
}
