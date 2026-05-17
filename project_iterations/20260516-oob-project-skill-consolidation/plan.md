# Plan

## 目标
- 将 Workbench Project 的创建、更新、蒸馏、HTML Display、Tool/API 设计、审查和 Project 文档维护统一收敛到 canonical skill：`oob-project`。
- 将旧边界名 `oob-native-workbench`、`oob-project-designer`、`oob-project-distiller` 替换为 `oob-project`，不保留 alias。

## 范围
- 修改 builtin skill manifest、`oob-project` skill 文档与 references。
- 修改 Workbench Runtime 中导出包、active Project context、内置 skill 读取路径。
- 修改 Prompt Runtime 边界说明和 agent context/docs 引用。
- 不重写 Workbench 数据格式、HTML bridge、Project Tool executor 或 public tool surface。

## 步骤
1. 建立迭代记录。
2. 合并 `oob-project-distiller` 的触发和 distillation reference 到 `oob-project`。
3. 删除独立 distiller manifest 入口和资源目录。
4. 将 runtime/export 里的旧 skill id/path/source 改为 `oob-project`。
5. 同步 Prompt Runtime 与 agent context/reference 文档。
6. 执行静态搜索和相关测试。

## 验收标准
- `rg "oob-native-workbench|oob-project-designer|oob-project-distiller"` 只允许历史 iteration 记录残留，当前代码/文档不再引用。
- `WorkbenchRuntime` 导出包包含 `skills/oob-project/SKILL.md`。
- Project 创建请求仍能匹配 `oob-project`。
- 相关单元测试通过，或明确记录无法执行的原因。
