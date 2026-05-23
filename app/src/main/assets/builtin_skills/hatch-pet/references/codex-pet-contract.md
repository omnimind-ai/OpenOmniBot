# Omnibot Pet Contract

## Sprite Atlas

- Format: PNG or WebP.
- Dimensions: `1536x1872`.
- Grid: 8 columns x 9 rows.
- Cell: `192x208`.
- Background: transparent.
- Unused cells: fully transparent.

The app uses the fixed row and column counts for previews and playback. Do not add labels, gutters, borders, grid lines, shadows outside the cell, or extra frames.

## Local Custom Pet Package

Place selectable Xiaowan pets under:

```text
/workspace/.omnibot/pets/<pet-id>/
  pet.json
  spritesheet.webp
```

Manifest shape:

```json
{
  "id": "pet-id",
  "displayName": "Pet Name",
  "description": "One short sentence.",
  "spritesheetPath": "spritesheet.webp"
}
```

The app scans `/workspace/.omnibot/pets/`. Use `imagePath` instead of `spritesheetPath` only for static fallback files such as `current.svg` or `current.png`.
