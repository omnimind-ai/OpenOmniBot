# Progress

- 2026-05-17: 将 `oob-project/SKILL.md` 从 750 行精简到 178 行，只保留总入口、边界、硬关卡、runtime contract 和 reference 导航。
- 2026-05-17: 新增 `oob-project/references/lifecycle-guide.md`，承接新建 Project、ProjectContract、创建、文档、更新和 runtime quick reference 细节。
- 2026-05-17: 同步 builtin skill manifest 的 description 与各 `SKILL.md` frontmatter，减少手动漂移。
- 2026-05-17: 新增 `BuiltinSkillManifestConsistencyTest`，校验 manifest/frontmatter/目录 flags，并保护 retired Project skill id 不进入 builtin manifest。
- 2026-05-17: 扩展 `SkillRuntimeBehaviorTest`，确认 Project 蒸馏请求仍命中 canonical `oob-project`。
- 2026-05-17: 清理 `app/src/main/assets/builtin_skills/oob-project/scripts/__pycache__/`。
- 2026-05-17: 在 `AgentSkillRuntime` 的 builtin skill seeding 中加入 retired Project builtin skill 清理，并补单测覆盖旧目录和 registry entry 迁移。
- 2026-05-17: 完成静态扫描、targeted tests、完整 app 单测、APK 构建、APK asset 检查和 `emulator-5554` 设备迁移 smoke。
