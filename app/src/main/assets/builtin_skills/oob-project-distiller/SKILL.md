---
name: oob-project-distiller
description: Distill an external product, GitHub repository, vibe-app share, technical document, article, pasted notes, screenshots, or product demo into an OOB Workbench Project proposal or implementation. Use when the user asks to analyze a project/link/doc and "总结成我们的 project", "蒸馏能力", "提取核心设计", "生成 soul", "把这个 app/项目变成 OOB Project", "产品分析", "GitHub 仓库分析", or preserve UI design, interaction logic, data model, backend AI workflows, and product intent without cloning proprietary assets.
---

# OOB Project Distiller

Use this skill to convert external material into an OOB-native Project design. The low-risk v1 analyzes user-provided or publicly available materials: GitHub repositories, README/docs, technical documents, pasted app descriptions, screenshots, and share text. It does not automate or scrape third-party apps unless the user explicitly starts a separate Android observation flow.

For detailed templates and checklists, read `references/distillation-guide.md`.

## Operating Principle

Distill **capabilities and design intent**, not the original product.

Preserve:
- user jobs-to-be-done
- information architecture
- data model
- interaction flow
- AI/backend capabilities
- visual system principles
- reusable implementation patterns

Do not preserve:
- logos, names, brand identity, icons, illustrations, proprietary copy, or paid content
- private user data from the source
- authentication bypasses, scraping bypasses, or anti-abuse circumvention
- code copied from incompatible licenses

## Workflow

1. Identify the source type: GitHub repo, link/share text, technical doc, screenshot bundle, or pasted description.
2. Collect enough source evidence:
   - For GitHub: README, docs, package metadata, screenshots, examples, core source files, and license.
   - For share links/docs: visible text, screenshots, feature claims, workflow descriptions, and any user-provided context.
   - For technical docs: API concepts, data entities, workflows, constraints, and examples.
3. Build a distilled capability map:
   - core user problem
   - workflows worth keeping
   - data entities and fields
   - UI surfaces
   - interaction patterns
   - backend/AI abilities needed in OOB
4. Decide whether the result should be:
   - a Project proposal only
   - a full `workbench_project_create`
   - an update to an existing Project
   - a reusable skill plus a Project
5. Generate `PROJECT_SOUL.md` content before implementation.
6. Design Project APIs before HTML.
7. Use HTML/CSS/native JS as the default frontend output. Do not generate React as the default; React may only be used as a build-time source when the final artifact is static `frontend/html`.
8. Map advanced behavior to Project Tools and agent workflows, not WebView-only JavaScript.
9. Validate the proposal against OOB capability limits before claiming it can be built.

## When Creating the Project

If the user asks to build it now:

1. Use `oob-project-designer` for the concrete create/update mechanics.
2. Use the distillation artifacts from this skill as input:
   - soul
   - entity/schema
   - API list
   - frontend interaction contract
   - AI workflow map
3. Create the Project with HTML files by default.
4. Write or update `PROJECT_SOUL.md` immediately after creation.
5. Keep the first version small: 2-4 APIs, one primary HTML screen, optional local detail page.

## Required Output Shape

For analysis-only requests, return:

1. Source summary
2. What to keep / what to drop
3. OOB Project concept
4. `PROJECT_SOUL.md` draft
5. Data model
6. API/tool design
7. HTML UI/interaction design
8. Backend AI workflow map
9. Risks and open questions

For build requests, implement the Project and include a concise summary of the generated soul, APIs, and frontend.

## Hard Rules

- Never clone a product one-to-one. Reframe it as an OOB-native personal tool.
- Never treat HTML as a browser. The Project WebView is a local renderer with `window.oob.*`.
- Never put business state only in frontend state. `project.items` is the source of truth.
- Never add Contacts/SMS-style sensitive access as an implicit capability. Require explicit user request, clear UX, and separate permission review.
- Never use external URLs as Project navigation. Local relative HTML pages are allowed; external links should be summarized or opened outside the Project only if the user asks.
- Never generate desktop-first landing pages for phone WebView output.
