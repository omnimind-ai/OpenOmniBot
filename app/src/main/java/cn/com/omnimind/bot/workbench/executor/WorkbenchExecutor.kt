package cn.com.omnimind.bot.workbench.executor

import cn.com.omnimind.bot.workbench.WorkbenchApiRecord

/**
 * Pluggable executor for Workbench Project Tools.
 *
 * Implement this interface and register via [WorkbenchExecutorRegistry.register] to add a new
 * executor kind without modifying [WorkbenchProjectStore]. All built-in executors
 * (native_project_collection, workspace_python_script, agent_task) are registered at
 * construction time; future executors (agent_task, workspace_script, dart_eval, etc.) add a new
 * file + one register() call.
 */
interface WorkbenchExecutor {
    /**
     * Execute a Project Tool call.
     *
     * @param projectId Owning project id.
     * @param api The registered API record being invoked.
     * @param inputs Caller-supplied input map (already validated against [WorkbenchApiRecord.inputSchema]).
     * @return Result map written to api_calls.jsonl and returned to the caller.
     */
    suspend fun execute(
        projectId: String,
        api: WorkbenchApiRecord,
        inputs: Map<String, Any?>
    ): Map<String, Any?>
}

/**
 * Registry mapping executorKind strings to [WorkbenchExecutor] implementations.
 *
 * Not thread-safe for registration (all registrations happen at startup before any concurrent
 * API calls). Lookup via [get] is safe for concurrent reads.
 */
class WorkbenchExecutorRegistry {
    private val byKind = mutableMapOf<String, WorkbenchExecutor>()

    /** Register an executor for [kind]. Overwrites any previous registration for that kind. */
    fun register(kind: String, executor: WorkbenchExecutor) {
        byKind[kind] = executor
    }

    /** Returns the executor for [kind], or null if none is registered. */
    fun get(kind: String): WorkbenchExecutor? = byKind[kind]

    /** Returns all registered executor kind strings. */
    fun kinds(): Set<String> = byKind.keys.toSet()
}
