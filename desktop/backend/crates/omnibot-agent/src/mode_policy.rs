use omnibot_llm::ChatCompletionTool;

pub const NORMAL_MODE: &str = "normal";
pub const SUBAGENT_MODE: &str = "subagent";

pub fn filter_tools_for_mode(
    tools: Vec<ChatCompletionTool>,
    conversation_mode: &str,
) -> Vec<ChatCompletionTool> {
    let restricted = restricted_for(conversation_mode);
    tools
        .into_iter()
        .filter(|t| !restricted.iter().any(|name| t.function.name == *name))
        .collect()
}

fn restricted_for(mode: &str) -> &'static [&'static str] {
    if mode == SUBAGENT_MODE {
        &[
            "subagent_dispatch",
            "schedule_task_create",
            "schedule_task_list",
            "calendar_event_create",
            "alarm_create",
        ]
    } else {
        &[]
    }
}
