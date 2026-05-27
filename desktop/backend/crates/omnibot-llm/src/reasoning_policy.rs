use std::time::{Duration, Instant};

/// Throttle policy for emitting `reasoning_content` deltas to UI.
///
/// Mirrors Android `ReasoningStreamUpdatePolicy`. The first chunk is emitted immediately,
/// subsequent chunks are coalesced with a minimum interval. A final `force_flush()` is
/// called when the stream completes so no trailing characters are lost.
pub struct ReasoningStreamPolicy {
    interval: Duration,
    last_emit: Option<Instant>,
    pending: String,
}

impl ReasoningStreamPolicy {
    pub const DEFAULT_INTERVAL_MS: u64 = 60;

    pub fn new() -> Self { Self::with_interval(Duration::from_millis(Self::DEFAULT_INTERVAL_MS)) }
    pub fn with_interval(interval: Duration) -> Self {
        Self { interval, last_emit: None, pending: String::new() }
    }

    /// Append a delta. Returns `Some(snapshot)` if it is time to emit, else None.
    pub fn append(&mut self, delta: &str, full: &str) -> Option<String> {
        if delta.is_empty() { return None; }
        self.pending.push_str(delta);
        let now = Instant::now();
        match self.last_emit {
            None => {
                self.last_emit = Some(now);
                self.pending.clear();
                Some(full.to_string())
            }
            Some(prev) if now.duration_since(prev) >= self.interval => {
                self.last_emit = Some(now);
                self.pending.clear();
                Some(full.to_string())
            }
            _ => None,
        }
    }

    /// Force-emit on stream completion if anything is pending.
    pub fn force_flush(&mut self, full: &str) -> Option<String> {
        if self.pending.is_empty() { return None; }
        self.pending.clear();
        self.last_emit = Some(Instant::now());
        Some(full.to_string())
    }
}

impl Default for ReasoningStreamPolicy {
    fn default() -> Self { Self::new() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_chunk_emits_immediately() {
        let mut p = ReasoningStreamPolicy::new();
        assert_eq!(p.append("hi", "hi"), Some("hi".to_string()));
    }

    #[test]
    fn force_flush_drains_pending() {
        let mut p = ReasoningStreamPolicy::new();
        p.append("a", "a"); // emits
        p.append("b", "ab"); // pending
        assert_eq!(p.force_flush("ab"), Some("ab".to_string()));
    }
}
