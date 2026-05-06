class ChatConversationLifecycleGuard {
  int _revision = 0;

  int capture() => _revision;

  int invalidate() {
    _revision += 1;
    return _revision;
  }

  bool isCurrent(int token) => token == _revision;
}
