<p align="center">
  <picture>
    <img alt="OpenOmniBot" src="docs/pic/OmniBot.png" width="50%">
  </picture>
</p>

<h3 align="center">
Your On-Device AI Assistant
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OpenOmniBot">
  <a href="https://github.com/omnimind-ai/OpenOmniBot/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/omnimind-ai/OpenOmniBot"></a>
  <br>
  <a href="https://omnimind.com.cn"><img src="https://img.shields.io/badge/About_us-万象智维-purple.svg?color=%234b0c77" alt="OmniMind"></a>
  <a href="https://linux.do"><img src="https://img.shields.io/badge/Linux_Do-Community-yellow.svg?color=%23ac3712" alt="Linux Do Community"></a>
  <a href="#community">
    <img src="https://img.shields.io/badge/WeChat-Group-lightgreen" alt="WeChat Group"/>
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
<a href="README.md"><b>English</b></a> 
|
<a href="README.zh-CN.md"><b>简体中文</b></a>
|
</p>

> Unlike traditional mobile AI chat apps, OpenOmniBot runs directly on your device and can operate your Android phone like a human, including apps, gestures, and system settings.

OpenOmniBot is an on-device AI agent built with native Android Kotlin and Flutter. Instead of stopping at chat, it focuses on the full loop of **understand -> decide -> execute -> reflect**.

<h2 id="core-capabilities">Core Capabilities</h2>

- **Extensible tool ecosystem**: Skills, Alpine environment, browser access, MCP, and Android system-level tools.
- **Phone task automation**: Uses vision models to understand and operate mobile interfaces.
- **System-level actions**: Supports scheduled tasks, alarms, calendar creation/query/update, and audio playback control.
- **Memory system**: Short-term and long-term memory with embedding support.
- **Productivity tools**: Read and write files, browse the workspace, use the browser, and access the terminal.

<h2 id="quick-start">Quick Start</h2>

<p align="center">
  <img src="docs/tutorial/one.png" alt="Example" width="260" />
</p>

### Configure the app

Open the settings page from the left sidebar:

<p align="center">
  <img src="docs/tutorial/two.png" alt="Configure AI capabilities" width="420" />
  <img src="docs/tutorial/three.png" alt="Configure AI providers" width="260" />
</p>

Then open the scenario model settings:

<p align="center">
  <img src="docs/tutorial/four.png" alt="Configure AI models" width="260" />
</p>

Note: `Memory embedding` requires an embedding model. For the best overall experience, the other scenarios should use multimodal or vision-capable models whenever possible.

<p align="center">
  <img src="docs/tutorial/five.png" alt="Alpine environment" width="260" />
</p>

The app usually initializes the Alpine environment automatically on startup, and you can also manage that environment from the same settings area.

<h2 id="use-cases">Use Cases</h2>

### Skills

You can ask OmniBot to install a skill by simply sending it the repository link. Recommended collection: https://github.com/OpenMinis/MinisSkills

Enable or disable skills from the skill repository:

<p align="center">
  <img src="docs/tutorial/six.png" alt="Skill store" width="260" />
  <img src="docs/tutorial/seven.png" alt="Skill example" width="260" />
</p>

### VLM tasks

<p align="center">
  <img src="docs/tutorial/eight.png" alt="VLM task" width="260" />
</p>

Before starting a task, open the chat page and grant all required permissions from the top-right corner.

### Local model inference

<p align="center">
  <img src="docs/tutorial/nine.png" alt="Local inference" width="260" />
</p>

Supports both MNN and llama backends.

### Scheduled tasks

<p align="center">
  <img src="docs/tutorial/ten.png" alt="Scheduled task" width="260" />
  <img src="docs/tutorial/eleven.png" alt="Timing" width="260" />
</p>

Scheduled tasks can execute work such as VLM tasks and subagent flows. Alarms are reminder-only. A subagent can be assigned a complete task and behaves like a full agent.

### Browser

<p align="center">
  <img src="docs/tutorial/twelve.png" alt="Browser" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

<h2 id="development-guide">Development Guide</h2>

### Requirements

- Flutter SDK `3.9.2+`
- JDK `11+`

### Get the code

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

git submodule update --init third_party/omniinfer
git -C third_party/omniinfer submodule update --init framework/mnn
git -C third_party/omniinfer submodule update --init framework/llama.cpp

cd ui
flutter pub get
```

If Flutter reports `Could not read script '.../ui/.android/include_flutter.groovy'`, run:

```bash
flutter clean
flutter pub get
```

### Build and install

```bash
cd ..
./gradlew :app:installDevelopDebug
```

<h2 id="architecture">Architecture Overview</h2>

```text
OpenOmniBot/
├── app/                        # Android host app: entry point, agent orchestration, system abilities, MCP, services
├── ui/                         # Flutter UI: chat, settings, tasks, memory, and web chat bundle
├── baselib/                    # Shared core libraries: database, storage, networking, model config, OCR, permissions
├── assists/                    # Automation engine: task scheduling, state machine, visual detection, execution control
├── accessibility/              # Accessibility and screen perception: accessibility service, screenshots, projection
├── omniintelligence/           # AI abstractions: model protocol, task status, request/response models
├── uikit/                      # Native overlay UI: floating ball, overlay panels, half-screen surfaces
├── third_party/omniinfer/      # Local inference runtime and Android integration modules
└── ReTerminal/core/            # Embedded terminal experience modules
```

<h2 id="community">Community</h2>

Thanks to the community （including [LINUX](linux.do)）developers supporting OpenOmniBot.

Special thanks to these open-source projects:

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="docs/pic/wechat.jpg" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">Join our Discord community</a>
    </td>
  </tr>
</table>
