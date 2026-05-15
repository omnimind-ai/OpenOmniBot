enum HabitualHand {
  left('left'),
  right('right');

  const HabitualHand(this.storageValue);

  final String storageValue;

  bool get isLeft => this == HabitualHand.left;
}

HabitualHand habitualHandFromStorageValue(String? value) {
  for (final hand in HabitualHand.values) {
    if (hand.storageValue == value) {
      return hand;
    }
  }
  return HabitualHand.right;
}
