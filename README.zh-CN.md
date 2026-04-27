<p align="center">
  <picture>
    <img alt="OpenOmniBot" src="docs/pic/OmniBot.png" width="50%">
  </picture>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> |
  <a href="README.zh-CN.md"><b>简体中文</b></a>
</p>

<h3 align="center">
你的端侧 AI 助手
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OpenOmniBot">
  <a href="https://github.com/omnimind-ai/OpenOmniBot/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/omnimind-ai/OpenOmniBot"></a>
  <br>
  <a href="https://omnimind.com.cn"><img src="https://img.shields.io/badge/About_us-万象智维-purple.svg?color=%234b0c77" alt="万象智维"></a>
  <a href="https://linux.do"><img src="https://img.shields.io/badge/Linux_Do-社区-yellow.svg?color=%23ac3712" alt="LinuxDo社区"></a>
  <a href="#community">
    <img src="https://img.shields.io/badge/WeChat-微信群-lightgreen" alt="微信群"/>
  </a>
</div>

<p align="center">
|
<a href="#use-cases"><b>Demo</b></a>
|
<a href="#quick-start"><b>Quick Start</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/releases"><b>Release</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/issues"><b>Issues</b></a>
|
</p>

> 与传统手机 AI 聊天不同，OpenOmniBot 在设备上运行，可以像人类一样控制您的安卓手机，包括应用、手势和系统设置。

OpenOmniBot 是一个基于 Android 原生 Kotlin 与 Flutter 构建的端侧 AI Agent。与传统 AI Chat 不同，它关注的是 **从理解 -> 决策 -> 执行 -> 反馈的完整闭环**。

<h2 id="core-capabilities">核心能力</h2>

- **工具生态扩展**：Skills、Alpine 环境、浏览器、MCP、安卓系统级工具等。
- **手机任务自动化**：支持使用视觉模型理解并操作手机界面。
- **系统级能力**：支持定时任务、闹钟提醒、日历创建/查询/修改、音频播放控制。
- **记忆系统**：支持短期与长期记忆嵌入。
- **生产力工具**：支持读写文件、浏览工作区、调用浏览器、调用终端。

<h2 id="quick-start">快速开始</h2>

<p align="center">
  <img src="docs/tutorial/example.jpg" alt="示例" width="260" />
</p>

### 配置

在左侧栏的设置页面内打开配置：

<p align="center">
  <img src="docs/tutorial/1.png" alt="设置 AI 能力" width="420" />
  <img src="docs/tutorial/2.png" alt="配置 AI 提供商" width="260" />
</p>

然后前往场景模型配置：

<p align="center">
  <img src="docs/tutorial/3.png" alt="配置 AI 模型" width="260" />
</p>

说明：除了 `Memory embedding` 强制需要嵌入模型之外，其他场景为了获得更好的体验，建议优先使用多模态或视觉模型。

<p align="center">
  <img src="docs/tutorial/alpine.jpg" alt="Alpine 环境" width="260" />
</p>

一般情况下，应用启动时会自动初始化 Alpine 环境，你也可以在这里自行管理环境。

<h2 id="use-cases">使用场景</h2>

### Skills

你可以直接把 skills 仓库链接发给小万，让她帮你安装。推荐仓库：https://github.com/OpenMinis/MinisSkills

在技能仓库中可以选择开启或关闭某项技能：

<p align="center">
  <img src="docs/tutorial/skills_store.jpg" alt="技能仓库" width="260" />
  <img src="docs/tutorial/skills_example.jpg" alt="技能示例" width="260" />
</p>

### VLM 任务

<p align="center">
  <img src="docs/tutorial/vlm.jpg" alt="VLM 任务" width="260" />
</p>

开始任务前，请先在聊天页右上角完成所有必要权限授权。

### 本地模型推理

<p align="center">
  <img src="docs/tutorial/local_inference.jpg" alt="本地推理" width="260" />
</p>

支持 MNN 和 llama 后端。

### 定时任务

<p align="center">
  <img src="docs/tutorial/timed.jpg" alt="定时任务" width="260" />
  <img src="docs/tutorial/timing.jpg" alt="时间设置" width="260" />
</p>

定时任务是可执行的任务，例如 VLM 任务和 subagent 流程；闹钟仅用于提醒。你也可以把一个完整任务交给 subagent，它会像完整 agent 一样执行。

### 浏览器

<p align="center">
  <img src="docs/tutorial/browser.jpg" alt="浏览器" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

<h2 id="development-guide">开发指南</h2>

### 环境要求

- Flutter SDK `3.9.2+`
- JDK `11+`

### 获取代码

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

git submodule update --init third_party/omniinfer
git -C third_party/omniinfer submodule update --init framework/mnn

cd ui
flutter pub get
```

如果 Flutter 提示 `Could not read script '.../ui/.android/include_flutter.groovy'`，可以执行：

```bash
flutter clean
flutter pub get
```

### 构建并安装

```bash
cd ..
./gradlew :app:installDevelopDebug
```

<h2 id="architecture">架构概览</h2>

```text
OpenOmniBot/
├── app/                        # Android 主宿主模块：入口、Agent 编排、系统能力、MCP、前台服务
├── ui/                         # Flutter UI 模块：聊天、设置、任务、记忆，以及 web chat bundle
├── baselib/                    # 基础核心库：数据库、存储、网络、模型配置、OCR、权限等
├── assists/                    # 自动化执行引擎：任务调度、状态机、视觉检测、执行控制
├── accessibility/              # 无障碍与屏幕感知：Accessibility Service、截图、投屏能力
├── omniintelligence/           # 智能能力抽象层：模型协议、任务状态、请求/响应模型
├── uikit/                      # 原生浮层 UI：悬浮球、覆盖层面板、半屏界面
├── third_party/omniinfer/      # 本地推理运行时及 Android 集成模块
└── ReTerminal/core/            # 内嵌终端体验相关模块
```

<h2 id="community">其他</h2>

感谢 [LINUX.DO](linux.do) 等社区开发者对 OpenOmniBot 的支持。

特别感谢这些优秀的开源项目：

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="docs/pic/wechat.jpg" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">加入 Discord 社区</a>
    </td>
  </tr>
</table>
