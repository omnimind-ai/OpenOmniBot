/// Lightweight registry so UserDialogCard can submit a user message without
/// threading callbacks through the full widget tree.
///
/// The active chat page registers its send-message handler on mount and clears
/// it on dispose. The dialog card calls [submit] when the user makes a choice.
class UserDialogRegistry {
  UserDialogRegistry._();

  static void Function(String text)? _handler;

  static void register(void Function(String text) handler) {
    _handler = handler;
  }

  static void clear() {
    _handler = null;
  }

  static void submit(String text) {
    _handler?.call(text);
  }
}
