use omnibot_llm::ChatCompletionMessage;
use parking_lot::Mutex;
use std::sync::Arc;

/// Simple in-memory transcript holder for one Agent run.
/// Persistence happens via `omnibot-db` outside this struct.
#[derive(Clone, Default)]
pub struct AgentChatMemory {
    inner: Arc<Mutex<Vec<ChatCompletionMessage>>>,
}

impl AgentChatMemory {
    pub fn new() -> Self { Self::default() }
    pub fn from_initial(seed: Vec<ChatCompletionMessage>) -> Self {
        Self { inner: Arc::new(Mutex::new(seed)) }
    }
    pub fn push(&self, msg: ChatCompletionMessage) {
        self.inner.lock().push(msg);
    }
    pub fn snapshot(&self) -> Vec<ChatCompletionMessage> {
        self.inner.lock().clone()
    }
    pub fn len(&self) -> usize { self.inner.lock().len() }
    pub fn is_empty(&self) -> bool { self.inner.lock().is_empty() }
    pub fn replace(&self, new_msgs: Vec<ChatCompletionMessage>) {
        let mut g = self.inner.lock();
        g.clear();
        g.extend(new_msgs);
    }
}
