# Omnibot App Update Worker

Cloudflare Worker for public app update checks, authenticated release metadata management, and APK delivery through Cloudflare R2. Release metadata and APK files are stored in R2; no KV namespace is required.

## Routes

- `GET /updates?currentVersion=0.5.0.3&edition=omniinfer&source=worker&includeBeta=true`
  - Public endpoint used by the Android app.
  - Reads release metadata from R2 without recording visit counters.
  - Returns `apkDownloadUrl` pointing at this Worker.
- `GET /downloads/:tag/:asset`
  - Public APK download endpoint backed by R2.
- `PUT /admin/releases/:tag/assets/:asset`
  - Requires `Authorization: Bearer <ADMIN_TOKEN>`.
  - Streams a small APK or `.apk.sha256` file into the bound R2 bucket.
- `POST /admin/releases/:tag/assets/:asset?action=mpu-create`
- `PUT /admin/releases/:tag/assets/:asset?action=mpu-uploadpart&uploadId=...&partNumber=...`
- `POST /admin/releases/:tag/assets/:asset?action=mpu-complete&uploadId=...`
- `DELETE /admin/releases/:tag/assets/:asset?action=mpu-abort&uploadId=...`
  - Requires admin auth.
  - Multipart upload flow for large APKs, keeping each Worker request below Cloudflare's request body limit.
- `POST /admin/releases`
  - Requires `Authorization: Bearer <ADMIN_TOKEN>`.
  - Upserts a release tag and APK assets.
- `DELETE /admin/releases/:tag`
  - Requires admin auth.
  - Removes a tag so clients stop seeing a retracted package.
- `GET /admin/releases`
  - Requires admin auth.

## Deploy

Create an R2 bucket, bind it to the Worker, then configure the admin token.

Dashboard binding:

- Resource type: `R2 bucket`
- Variable name: `APP_UPDATE_BUCKET`
- R2 bucket: the bucket that stores APK files and release metadata

Wrangler deployment:

```bash
wrangler r2 bucket create omnibot-app-updates
cp wrangler.toml.example wrangler.toml
# Put the created bucket name in wrangler.toml.
wrangler secret put ADMIN_TOKEN
wrangler deploy
```

Use the deployed Worker URL as:

- Android Gradle property: `OMNIBOT_UPDATE_WORKER_URL`
- GitHub Actions secret: `APP_UPDATE_WORKER_URL`

Use the same token as the GitHub Actions secret `APP_UPDATE_WORKER_TOKEN`.

GitHub release publishing uploads staged APKs with the helper script. Large APKs use Worker-backed R2 multipart upload automatically:

```bash
python3 scripts/upload_release_asset_to_worker.py \
  --worker-url "$APP_UPDATE_WORKER_URL" \
  --token "$APP_UPDATE_WORKER_TOKEN" \
  --tag v1.6.2 \
  --file OpenOmniBot-v1.6.2-omniinfer.apk \
  --content-type application/vnd.android.package-archive \
  --sha256 "$APK_SHA256"
```
