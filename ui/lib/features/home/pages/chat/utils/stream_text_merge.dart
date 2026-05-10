bool shouldIgnoreRegressiveStreamingSnapshot(String current, String incoming) {
  if (current.isEmpty || incoming.isEmpty) {
    return false;
  }
  return incoming.length < current.length && current.startsWith(incoming);
}

String mergeAgentTextSnapshot(String current, String incoming) {
  if (incoming.isEmpty) return current;
  if (current.isEmpty) return incoming;
  if (incoming == current) return current;
  if (shouldIgnoreRegressiveStreamingSnapshot(current, incoming)) {
    return current;
  }
  return incoming;
}

String mergeLegacyStreamingText(String current, String incoming) {
  if (incoming.isEmpty) return current;
  if (current.isEmpty) return incoming;
  if (incoming == current) return current;
  if (shouldIgnoreRegressiveStreamingSnapshot(current, incoming)) {
    return current;
  }
  if (incoming.length >= current.length && incoming.startsWith(current)) {
    return incoming;
  }
  return current + incoming;
}
