---
name: hatch-pet
description: Create or update an Omnibot / Xiaowan custom pet from a concept, reference image, or bare company name. Use when the user wants a selectable pet package, animated 8x9 sprite atlas, brand-discovery-driven mascot, or static fallback pet.
---

# Hatch Pet

Build a pet package that shows up in `外观设置 > 宠物` and is written to:

```text
/workspace/.omnibot/pets/<pet-id>/pet.json
/workspace/.omnibot/pets/<pet-id>/spritesheet.webp
```

Prefer the animated atlas path. Static files such as `current.svg` or `current.png` are allowed only when the user explicitly asks for a static pet or when `image_generate` is unavailable after you have told the user.

Never write the selectable pet package under a desktop Codex directory such as `${HOME}/.codex/pets`, `~/.codex/pets`, or `/workspace/.codex/pets`.

If the user gives `宠物名称` (`瀹犵墿鍚嶇О`), use that exact name as `displayName`, and use the same name for the folder/id after replacing only path-unsafe characters such as `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, and whitespace.

Animated `pet.json` example:

```json
{
  "id": "mimibear",
  "displayName": "mimibear",
  "description": "像素风小白熊，圆润可爱。",
  "spritesheetPath": "spritesheet.webp"
}
```

## Generation Provider

Codex uses `$imagegen` for image creation, but Xiaowan does not have a desktop Codex `$imagegen` skill. In Xiaowan, use the `image_generate` tool for normal visual generation. It calls the configured OpenAI-compatible image provider, decodes the returned image, and writes a real PNG/WebP/JPEG file into the workspace.

Use `file_write` only for manifests, `pet.json`, static SVG fallbacks, or copying already-generated base64 image bytes. Do not use `file_write` to hand-write fake raster art when `image_generate` is available.

If no image-capable provider is available, fall back to a static SVG/PNG pet only after telling the user that animated image generation is unavailable. Do not fake an animated pet with placeholder files.

`image_generate` requires an OpenAI-compatible image credential. Xiaowan's default image endpoint is `https://cloud.omnimind.com.cn`, and the default image model is `gpt-image-2`. For first-party builds, the app can bundle the company image credential through the build-time `OMNIBOT_IMAGE_API_KEY` secret so users do not need to enter a key. Do not write API keys into skill files, pet manifests, prompts, or generated artifacts.

For animated pets, generate the same Codex-compatible atlas contract:

- one base reference image
- 9 animation rows: `idle`, `running-right`, `running-left`, `waving`, `jumping`, `failed`, `waiting`, `running`, `review`
- final atlas size `1536x1872`, arranged as 8 columns x 9 rows with `192x208` cells
- transparent background and fully transparent unused cells

Xiaowan can display this atlas dynamically: after the package lands under `/workspace/.omnibot/pets/<pet-id>/`, the settings page uses a generated preview for the pet list, while the floating pet uses the original `spritesheet.webp` or `spritesheet.png` for animation playback.

State row rules mirror the Codex hatch-pet contract:

- `idle`: subtle breathing, blink, or tiny body bob only; the loop must visibly move.
- `running-right` and `running-left`: directional drag movement, facing the correct direction; no speed lines, dust, shadows, or motion trails. Generate `running-right` first and mirror `running-left` only when identity and prop placement still make sense.
- `waving`: show the wave through limb pose only; no floating wave marks or symbols.
- `jumping`: show vertical body motion only; no floor marks, dust, or shadows.
- `failed`: sad/error emotion through pose or attached opaque effects only; no detached symbols.
- `waiting`: expectant help/approval pose, distinct from idle.
- `running`: active task work or thinking motion, not literal foot-running.
- `review`: focused review pose; avoid new props unless the base pet already has them.

## References

Read these when needed:

- `references/codex-pet-contract.md`
- `references/animation-rows.md`
- `references/qa-rubric.md`

## Workflow

1. If the user gives only a brand, company, product, or prospect name, run a lightweight brand-discovery subagent first. Search the web narrowly, prefer official sources, and extract palette, tone, motifs, and mascot-safe cues. Save the brief before generation.
2. Prepare the run with `scripts/prepare_pet_run.py`. Pass the chosen name, short description, pet notes, references, style preset, and brand-discovery file when relevant. Let the script create the layout guides, prompt files, and `imagegen-jobs.json`.
3. Generate visuals with `image_generate`. Base first, then each row strip. Use the prompt file as the `prompt`, set `outputPath` to the job's decoded output path or a temporary workspace image path, and use `model: gpt-image-2`, `format: png`, `background: transparent`, and `size: 1536x1024` for row strips when supported. If a job lists input images, summarize their identity/layout constraints into the prompt until Xiaowan has an image-edit/reference tool. Respect the transparency rules: flat removable background, no scenery, no labels, no detached effects, no shadows, no glow, no dust, no motion trails.
4. After selecting each output, copy it into the decoded path and mark the job complete. Derive `running-left` with `scripts/derive_running_left_from_running_right.py` only when mirroring preserves identity, cadence, and prop placement.
5. Run the deterministic post-pass scripts in order: `extract_strip_frames.py`, `inspect_frames.py`, `compose_atlas.py`, `validate_atlas.py`, `make_contact_sheet.py`, and `render_animation_previews.py`. Use `stable-slots` only if QA shows extraction-induced popping and the source strip itself was already stable.
6. Visually QA the contact sheet and preview GIFs. Repair the smallest failing row only. Keep the same silhouette, face, palette, materials, props, and style across all rows.
7. Package the final pet into `/workspace/.omnibot/pets/<pet-id>/` with `pet.json` plus `spritesheet.webp`. If you must fall back to a static pet, write `imagePath` with `current.svg` or `current.png` instead.

## Pet Rules

- Keep the body compact and readable at `192x208` per cell.
- Preserve identity across every row.
- `idle` must stay calm and low-distraction.
- `running` is task-work motion, not literal foot-running.
- `waving`, `jumping`, `failed`, `waiting`, and `review` each need distinct semantics.
- For brand-only requests, use the discovery brief as a mascot-safe cue set. Do not copy logos, readable marks, UI screenshots, slogans, or text.
- Preserve Chinese pet names when the user provides them. Only remove path-unsafe characters and collapse whitespace as needed.

## Output Rules

- Do not create files outside `/workspace/.omnibot/pets/<pet-id>/`.
- Do not stop after writing only the art file. `pet.json` must exist next to it.
- Do not create a top-level `/workspace/.omnibot/pets/current.svg` or `/workspace/.omnibot/pets/spritesheet.webp` for a named pet.
- If writing an image through `file_write`, supply real image bytes or a valid data URI/base64 payload.
- If the pet is animated, `pet.json` should use `spritesheetPath`. If the pet is static, use `imagePath`.

## Failure Handling

If any required hatch-pet write fails for `/workspace/.omnibot/pets/<pet-id>/spritesheet.webp`, `/workspace/.omnibot/pets/<pet-id>/current.svg`, `/workspace/.omnibot/pets/<pet-id>/current.png`, or `/workspace/.omnibot/pets/<pet-id>/pet.json`, report the exact failure and do not switch to an unrelated path.
