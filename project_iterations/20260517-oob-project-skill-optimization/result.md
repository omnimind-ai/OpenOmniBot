# Result

## 状态
- completed

## 一句话执行记录
- canonical `oob-project` 主 skill 已精简为总入口，长生命周期流程下沉到 references；manifest/frontmatter/trigger/retired-id 守护测试已补齐，已安装设备 workspace 的 retired Project builtin skill 目录会在启动 seeding 时迁移清理。

## 验证
- `python3 -m json.tool app/src/main/assets/builtin_skills/manifest.json`：通过。
- `rg -n "oob-native-workbench|oob-project-designer|oob-project-distiller" -g '!project_iterations/**' -g '!**/__pycache__/**' -g '!workspace/**'`：无命中。
- `./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.agent.BuiltinSkillManifestConsistencyTest --tests cn.com.omnimind.bot.agent.SkillRuntimeBehaviorTest`：通过。
- `./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest`：通过。
- `./gradlew --no-daemon :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`：通过。
- APK asset 检查：包含 `assets/builtin_skills/oob-project/SKILL.md`、`references/lifecycle-guide.md`、`references/distillation-guide.md`；不包含 retired Project skill asset。
- `emulator-5554` smoke：安装前存在旧 `workspace/.omnibot/skills/oob-project-distiller`，安装并启动新 APK 后目录被清理，`.skill_registry.json` 只保留当前 builtin skills，`oob-project/SKILL.md` 可读。
