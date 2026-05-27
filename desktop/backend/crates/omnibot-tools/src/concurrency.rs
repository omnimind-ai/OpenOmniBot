use crate::types::ToolConcurrencyHint;

/// Greedy batch-partition strategy:
/// - Consecutive ParallelSafe calls are grouped into one batch
/// - A SerialBarrier call always sits in its own singleton batch
#[derive(Debug, Clone)]
pub enum ToolBatch<T> {
    Parallel(Vec<T>),
    Serial(T),
}

pub fn partition<T>(calls: Vec<T>, hint: impl Fn(&T) -> ToolConcurrencyHint) -> Vec<ToolBatch<T>> {
    let mut out: Vec<ToolBatch<T>> = vec![];
    let mut parallel: Vec<T> = vec![];
    for c in calls {
        match hint(&c) {
            ToolConcurrencyHint::SerialBarrier => {
                if !parallel.is_empty() {
                    out.push(ToolBatch::Parallel(std::mem::take(&mut parallel)));
                }
                out.push(ToolBatch::Serial(c));
            }
            ToolConcurrencyHint::ParallelSafe => parallel.push(c),
        }
    }
    if !parallel.is_empty() { out.push(ToolBatch::Parallel(parallel)); }
    out
}
