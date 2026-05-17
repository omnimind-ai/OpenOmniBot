# Plan

## 背景

在 `oob-project` consolidation 后继续做结构优化：降低主 skill 默认加载体量，补守护测试，清理生成物，并尽量补设备 smoke。

## 范围

- 精简 `app/src/main/assets/builtin_skills/oob-project/SKILL.md`。
- 将 Project 创建/更新详细流程下沉到 `references/lifecycle-guide.md`。
- 增加 builtin skill manifest 与 `SKILL.md` frontmatter/目录结构一致性测试。
- 增加 canonical `oob-project` 触发与 retired Project skill id 负向保护。
- 清理 `oob-project/scripts/__pycache__/`。
- 增加已安装 workspace 中 retired builtin Project skill 目录/registry 的启动迁移清理。
- 跑静态检查、单元测试，并尝试设备 smoke。
