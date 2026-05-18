const DEFAULT_GITHUB_REPO = "omnimind-ai/OpenOmniBot";
const DEFAULT_EDITIONS = ["omniinfer", "standard"];
const DEFAULT_R2_RELEASES_PREFIX = "releases";
const DEFAULT_R2_METADATA_PREFIX = "metadata/releases";
const DOWNLOAD_ROUTE_PREFIX = "/downloads/";
const ADMIN_RELEASE_ROUTE_PREFIX = "/admin/releases/";
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const pathname = normalizePath(url.pathname);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...JSON_HEADERS,
          "access-control-allow-methods": "GET,HEAD,POST,PUT,DELETE,OPTIONS",
          "access-control-allow-headers": "authorization,content-type,x-content-sha256,x-update-token",
        },
      });
    }

    try {
      if ((request.method === "GET" || request.method === "HEAD") && pathname.startsWith(DOWNLOAD_ROUTE_PREFIX)) {
        return handleDownloadAsset(request, url, env);
      }

      if (request.method === "GET" && pathname === "/") {
        return json({
          ok: true,
          service: "omnibot-app-update-worker",
          storage: "r2",
          routes: [
            "/updates",
            "/downloads/:tag/:asset",
            "/admin/releases",
            "/admin/releases/:tag",
            "/admin/releases/:tag/assets/:asset",
          ],
        });
      }

      if (request.method === "GET" && pathname === "/updates") {
        return handleUpdateCheck(url, env);
      }

      if (pathname === "/admin/releases" && request.method === "GET") {
        requireAdmin(request, env);
        return handleListReleases(env);
      }

      if (pathname === "/admin/releases" && request.method === "POST") {
        requireAdmin(request, env);
        return handleUpsertRelease(request, env);
      }

      if (
        (request.method === "POST" || request.method === "PUT" || request.method === "DELETE") &&
        pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX) &&
        pathname.includes("/assets/")
      ) {
        requireAdmin(request, env);
        return handleAssetMutation(request, url, env);
      }

      if (pathname === "/admin/releases" && request.method === "DELETE") {
        requireAdmin(request, env);
        return handleDeleteRelease(url.searchParams.get("tag"), env);
      }

      if (pathname.startsWith("/admin/releases/") && request.method === "DELETE") {
        requireAdmin(request, env);
        return handleDeleteRelease(decodeURIComponent(pathname.slice("/admin/releases/".length)), env);
      }

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      const status = Number.isInteger(error.status) ? error.status : 500;
      return json({ ok: false, error: error.message || "Internal error" }, status);
    }
  },
};

async function handleUpdateCheck(url, env) {
  const currentVersion = normalizeVersion(
    url.searchParams.get("currentVersion") ||
      url.searchParams.get("current_version") ||
      url.searchParams.get("version") ||
      "",
  );
  const includeBeta = parseBoolean(url.searchParams.get("includeBeta") || url.searchParams.get("include_beta"));
  const edition = normalizeEdition(url.searchParams.get("edition"));
  const source = normalizeSource(url.searchParams.get("source") || env.DEFAULT_SOURCE || "worker");
  const checkedAt = Date.now();

  const releases = await loadReleases(requireBucket(env), env);
  const selected = selectLatestRelease(releases, includeBeta);
  if (!selected) {
    return json(emptyUpdateResponse({ currentVersion, checkedAt, edition, source }));
  }

  const asset = selectPreferredApkAsset(selected.assets, edition);
  const latestVersion = selected.version;
  const hasUpdate = Boolean(asset) && compareVersions(latestVersion, currentVersion) > 0;

  return json({
    ok: true,
    currentVersion,
    latestVersion,
    hasUpdate,
    checkedAt,
    publishedAt: selected.publishedAt || 0,
    tag: selected.tag,
    track: selected.track,
    releaseUrl: selected.releaseUrl || "",
    releaseNotes: selected.releaseNotes || "",
    apkName: asset?.name || "",
    apkDownloadUrl: asset ? assetDownloadUrl(asset, source, url, selected.tag) : "",
    edition,
    source,
    assets: (selected.assets || []).map((releaseAsset) => publicAsset(releaseAsset, url, selected.tag)),
  });
}

async function handleListReleases(env) {
  const releases = await loadReleases(requireBucket(env), env, { includeDrafts: true });
  return json({ ok: true, releases });
}

async function handleUpsertRelease(request, env) {
  const bucket = requireBucket(env);
  const body = await readJson(request);
  const release = normalizeRelease(body, env);
  await bucket.put(releaseObjectKey(release.tag, env), JSON.stringify(release), releaseMetadataOptions(release));
  return json({ ok: true, release });
}

async function handleAssetMutation(request, url, env) {
  const action = stringValue(url.searchParams.get("action"));
  if (!action && request.method === "PUT") {
    return handleUploadAsset(request, url, env);
  }
  if (action === "mpu-create" && request.method === "POST") {
    return handleCreateMultipartUpload(request, url, env);
  }
  if (action === "mpu-uploadpart" && request.method === "PUT") {
    return handleUploadMultipartPart(request, url, env);
  }
  if (action === "mpu-complete" && request.method === "POST") {
    return handleCompleteMultipartUpload(request, url, env);
  }
  if (action === "mpu-abort" && request.method === "DELETE") {
    return handleAbortMultipartUpload(url, env);
  }
  throw httpError(400, "unsupported asset upload action");
}

async function handleUploadAsset(request, url, env) {
  const bucket = requireBucket(env);
  const parsed = requireAdminAssetPath(url);
  const { tag, name } = parsed;
  validateStoredAssetName(name);
  if (!request.body) {
    throw httpError(400, "asset body is required");
  }

  const contentType = request.headers.get("content-type") || contentTypeForAssetName(name);
  const sha256 = stringValue(request.headers.get("x-content-sha256"));
  const size = normalizeSize(request.headers.get("content-length"));
  const key = assetObjectKey(tag, name, env);
  const uploadedAt = Date.now();

  const uploaded = await bucket.put(key, request.body, assetUploadOptions({
    tag,
    name,
    contentType,
    sha256,
    size,
    uploadedAt,
  }));

  const workerDownloadUrl = publicDownloadUrl(url, tag, name);
  return json({
    ok: true,
    asset: {
      name,
      r2ObjectKey: key,
      workerDownloadUrl,
      downloadUrl: workerDownloadUrl,
      sha256,
      size,
      etag: uploaded?.etag || "",
      uploadedAt,
    },
  });
}

async function handleCreateMultipartUpload(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const key = assetObjectKey(tag, name, env);
  const sha256 = stringValue(request.headers.get("x-content-sha256"));
  const size = normalizeSize(request.headers.get("x-content-size") || request.headers.get("content-length"));
  const uploadedAt = Date.now();
  const multipartUpload = await bucket.createMultipartUpload(key, assetUploadOptions({
    tag,
    name,
    contentType: request.headers.get("content-type") || contentTypeForAssetName(name),
    sha256,
    size,
    uploadedAt,
  }));

  return json({
    ok: true,
    upload: {
      key: multipartUpload.key,
      uploadId: multipartUpload.uploadId,
      uploadedAt,
    },
  });
}

async function handleUploadMultipartPart(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);
  if (!request.body) {
    throw httpError(400, "part body is required");
  }

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  const partNumber = Number(url.searchParams.get("partNumber"));
  if (!uploadId || !Number.isInteger(partNumber) || partNumber < 1 || partNumber > 10000) {
    throw httpError(400, "valid uploadId and partNumber are required");
  }

  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  try {
    const uploadedPart = await multipartUpload.uploadPart(partNumber, request.body);
    return json({ ok: true, part: uploadedPart });
  } catch (error) {
    throw httpError(400, error.message || "multipart part upload failed");
  }
}

async function handleCompleteMultipartUpload(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  if (!uploadId) {
    throw httpError(400, "uploadId is required");
  }

  const body = await readJson(request);
  const parts = normalizeUploadedParts(body.parts);
  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  let object;
  try {
    object = await multipartUpload.complete(parts);
  } catch (error) {
    throw httpError(400, error.message || "multipart upload complete failed");
  }

  const workerDownloadUrl = publicDownloadUrl(url, tag, name);
  return json({
    ok: true,
    asset: {
      name,
      r2ObjectKey: object.key,
      workerDownloadUrl,
      downloadUrl: workerDownloadUrl,
      sha256: stringValue(body.sha256),
      size: normalizeSize(body.size),
      etag: object.httpEtag || object.etag || "",
      uploadedAt: normalizeTimestamp(body.uploadedAt || Date.now()),
    },
  });
}

async function handleAbortMultipartUpload(url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  if (!uploadId) {
    throw httpError(400, "uploadId is required");
  }

  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  try {
    await multipartUpload.abort();
  } catch (error) {
    throw httpError(400, error.message || "multipart upload abort failed");
  }
  return json({ ok: true, aborted: true });
}

async function handleDownloadAsset(request, url, env) {
  const bucket = requireBucket(env);
  const parsed = parseDownloadPath(normalizePath(url.pathname));
  if (!parsed) {
    throw httpError(404, "Download route not found");
  }

  const { tag, name } = parsed;
  validateStoredAssetName(name);
  const object = await bucket.get(assetObjectKey(tag, name, env));
  if (!object) {
    throw httpError(404, "Asset not found");
  }

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  const etag = object.httpEtag || object.etag || "";
  if (etag) {
    headers.set("etag", etag);
  }
  headers.set("cache-control", isApkAssetName(name) ? "public, max-age=300" : "public, max-age=60");
  headers.set("content-disposition", `attachment; filename="${headerFileName(name)}"`);
  headers.set("access-control-allow-origin", "*");

  return new Response(request.method === "HEAD" ? null : object.body, {
    status: 200,
    headers,
  });
}

async function handleDeleteRelease(rawTag, env) {
  const bucket = requireBucket(env);
  const tag = normalizeTag(rawTag);
  if (!tag) {
    throw httpError(400, "tag is required");
  }

  const key = releaseObjectKey(tag, env);
  const existing = await bucket.head(key);
  const deleted = Boolean(existing);
  if (deleted) {
    await bucket.delete(key);
  }

  return json({ ok: true, tag, deleted });
}

async function loadReleases(bucket, env, { includeDrafts = false } = {}) {
  const releases = [];
  let cursor;
  const prefix = `${normalizeMetadataPrefix(env.R2_METADATA_PREFIX)}/`;

  do {
    const page = await bucket.list({ prefix, cursor });
    for (const object of page.objects || []) {
      const release = await readReleaseMetadata(bucket, object.key);
      if (release) {
        releases.push(release);
      }
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);

  return releases
    .filter((release) => includeDrafts || (!release.draft && release.track !== "unsupported"))
    .sort((left, right) => {
      const versionOrder = compareVersions(right.version, left.version);
      if (versionOrder !== 0) return versionOrder;
      return (right.publishedAt || 0) - (left.publishedAt || 0);
    });
}

function requireBucket(env) {
  if (!env.APP_UPDATE_BUCKET) {
    throw httpError(500, "APP_UPDATE_BUCKET R2 bucket binding is missing");
  }
  return env.APP_UPDATE_BUCKET;
}

function requireAdmin(request, env) {
  const expected = env.ADMIN_TOKEN || env.APP_UPDATE_WORKER_TOKEN;
  if (!expected) {
    throw httpError(500, "ADMIN_TOKEN is not configured");
  }

  const auth = request.headers.get("authorization") || "";
  const bearerToken = auth.replace(/^Bearer\s+/i, "").trim();
  const headerToken = (request.headers.get("x-update-token") || "").trim();
  if (bearerToken !== expected && headerToken !== expected) {
    throw httpError(401, "Unauthorized");
  }
}

function normalizeRelease(input, env) {
  if (!input || typeof input !== "object") {
    throw httpError(400, "JSON object body is required");
  }

  const tag = normalizeTag(input.tag || input.tagName || input.tag_name);
  if (!tag) {
    throw httpError(400, "tag is required");
  }

  const version = normalizeVersion(input.version || input.latestVersion || tag);
  const track = normalizeTrack(input.track) || classifyReleaseTrack(version, input.prerelease);
  const publishedAt = normalizeTimestamp(input.publishedAt || input.published_at || Date.now());
  const assets = normalizeAssets(input.assets, tag, env);

  return {
    tag,
    version,
    track,
    draft: Boolean(input.draft),
    prerelease: Boolean(input.prerelease),
    publishedAt,
    releaseUrl: stringValue(input.releaseUrl || input.htmlUrl || input.html_url || input.url),
    releaseNotes: stringValue(input.releaseNotes || input.notes || input.body),
    assets,
    updatedAt: Date.now(),
  };
}

function normalizeAssets(rawAssets, tag, env) {
  const assets = Array.isArray(rawAssets)
    ? rawAssets.map((asset) => normalizeAsset(asset, tag, env)).filter(Boolean)
    : [];

  if (assets.length > 0) {
    return assets;
  }

  return DEFAULT_EDITIONS.map((edition) => buildDefaultAsset(tag, edition, env));
}

function normalizeAsset(asset, tag, env) {
  if (!asset || typeof asset !== "object") return null;
  const name = stringValue(asset.name || asset.fileName || asset.filename);
  if (!name.toLowerCase().endsWith(".apk")) return null;
  const r2ObjectKey = stringValue(asset.r2ObjectKey || asset.r2_object_key || asset.key) ||
    assetObjectKey(tag, name, env);
  return {
    name,
    r2ObjectKey,
    downloadUrl: stringValue(asset.downloadUrl || asset.browser_download_url),
    workerDownloadUrl: stringValue(asset.workerDownloadUrl || asset.worker_download_url),
    r2DownloadUrl: stringValue(asset.r2DownloadUrl || asset.r2_download_url),
    githubDownloadUrl: stringValue(asset.githubDownloadUrl || asset.github_download_url || asset.browser_download_url),
    cnbDownloadUrl: stringValue(asset.cnbDownloadUrl || asset.cnb_download_url),
    sha256: stringValue(asset.sha256 || asset.sha256sum || asset.checksum),
    size: normalizeSize(asset.size || asset.contentLength || asset.content_length),
  };
}

function buildDefaultAsset(tag, edition, env) {
  const name = `OpenOmniBot-${tag}-${edition}.apk`;
  const githubRepo = env.GITHUB_REPO || DEFAULT_GITHUB_REPO;
  return {
    name,
    r2ObjectKey: assetObjectKey(tag, name, env),
    githubDownloadUrl: `https://github.com/${githubRepo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`,
  };
}

function selectLatestRelease(releases, includeBeta) {
  return releases
    .filter((release) => release.track === "stable" || (includeBeta && release.track === "beta"))
    .reduce((selected, release) => {
      if (!selected) return release;
      const versionOrder = compareVersions(release.version, selected.version);
      if (versionOrder > 0) return release;
      if (versionOrder === 0 && (release.publishedAt || 0) > (selected.publishedAt || 0)) {
        return release;
      }
      return selected;
    }, null);
}

function selectPreferredApkAsset(assets, edition) {
  const apkAssets = (assets || []).filter((asset) => asset.name.toLowerCase().endsWith(".apk"));
  const editionAsset = apkAssets.find((asset) => isEditionApkAsset(asset.name, edition));
  if (editionAsset) return editionAsset;
  if (apkAssets.some((asset) => isKnownEditionApkAsset(asset.name))) return null;
  return apkAssets.find((asset) => /^OpenOmniBot-v/i.test(asset.name)) || apkAssets[0] || null;
}

function assetDownloadUrl(asset, source, url, tag) {
  const workerDownloadUrl = assetWorkerDownloadUrl(asset, url, tag);
  if (source === "github") {
    return asset.githubDownloadUrl || asset.downloadUrl || workerDownloadUrl || "";
  }
  return workerDownloadUrl || asset.downloadUrl || asset.githubDownloadUrl || asset.cnbDownloadUrl || "";
}

function publicAsset(asset, url, tag) {
  const workerDownloadUrl = assetWorkerDownloadUrl(asset, url, tag);
  return {
    name: asset.name,
    downloadUrl: workerDownloadUrl || asset.downloadUrl || "",
    workerDownloadUrl,
    r2DownloadUrl: asset.r2DownloadUrl || "",
    r2ObjectKey: asset.r2ObjectKey || "",
    githubDownloadUrl: asset.githubDownloadUrl || "",
    cnbDownloadUrl: asset.cnbDownloadUrl || "",
    sha256: asset.sha256 || "",
    size: normalizeSize(asset.size),
  };
}

function assetWorkerDownloadUrl(asset, url, tag) {
  return asset.workerDownloadUrl || asset.r2DownloadUrl || (asset.name ? publicDownloadUrl(url, tag, asset.name) : "");
}

function requireAdminAssetPath(url) {
  const parsed = parseAdminAssetPath(normalizePath(url.pathname));
  if (!parsed) {
    throw httpError(404, "Upload route not found");
  }
  return parsed;
}

function parseDownloadPath(pathname) {
  const rest = pathname.slice(DOWNLOAD_ROUTE_PREFIX.length);
  const separator = rest.indexOf("/");
  if (separator <= 0 || separator >= rest.length - 1) return null;
  return {
    tag: normalizeTag(decodePathSegment(rest.slice(0, separator))),
    name: decodePathSegment(rest.slice(separator + 1)),
  };
}

function parseAdminAssetPath(pathname) {
  if (!pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX)) return null;
  const rest = pathname.slice(ADMIN_RELEASE_ROUTE_PREFIX.length);
  const marker = "/assets/";
  const markerIndex = rest.indexOf(marker);
  if (markerIndex <= 0 || markerIndex >= rest.length - marker.length) return null;
  return {
    tag: normalizeTag(decodePathSegment(rest.slice(0, markerIndex))),
    name: decodePathSegment(rest.slice(markerIndex + marker.length)),
  };
}

function publicDownloadUrl(url, tag, name) {
  return `${url.origin}${DOWNLOAD_ROUTE_PREFIX}${encodePathSegment(tag)}/${encodePathSegment(name)}`;
}

function assetObjectKey(tag, name, env) {
  const prefix = normalizeR2Prefix(env.R2_RELEASES_PREFIX || DEFAULT_R2_RELEASES_PREFIX);
  return `${prefix}/${encodePathSegment(tag)}/${encodePathSegment(name)}`;
}

function normalizeR2Prefix(raw) {
  return stringValue(raw).replace(/^\/+|\/+$/g, "") || DEFAULT_R2_RELEASES_PREFIX;
}

function releaseObjectKey(tag, env) {
  return `${normalizeMetadataPrefix(env.R2_METADATA_PREFIX)}/${encodePathSegment(tag)}.json`;
}

function normalizeMetadataPrefix(raw) {
  return stringValue(raw).replace(/^\/+|\/+$/g, "") || DEFAULT_R2_METADATA_PREFIX;
}

function releaseMetadataOptions(release) {
  return {
    httpMetadata: {
      contentType: "application/json; charset=utf-8",
    },
    customMetadata: omitEmpty({
      tag: release.tag,
      version: release.version,
      track: release.track,
      publishedAt: release.publishedAt ? String(release.publishedAt) : "",
    }),
  };
}

async function readReleaseMetadata(bucket, key) {
  const object = await bucket.get(key);
  if (!object) return null;
  try {
    return JSON.parse(await object.text());
  } catch {
    return null;
  }
}

function validateStoredAssetName(name) {
  const value = stringValue(name);
  if (!value || value.includes("/") || value.includes("\\") || value === "." || value === "..") {
    throw httpError(400, "invalid asset name");
  }
  if (!isApkAssetName(value) && !value.toLowerCase().endsWith(".apk.sha256")) {
    throw httpError(400, "only APK assets and APK SHA-256 files are supported");
  }
}

function isApkAssetName(name) {
  return stringValue(name).toLowerCase().endsWith(".apk");
}

function contentTypeForAssetName(name) {
  return isApkAssetName(name) ? "application/vnd.android.package-archive" : "text/plain; charset=utf-8";
}

function headerFileName(name) {
  return stringValue(name).replace(/["\r\n]/g, "_");
}

function assetUploadOptions({ tag, name, contentType, sha256, size, uploadedAt }) {
  return {
    httpMetadata: {
      contentType: contentType || contentTypeForAssetName(name),
      contentDisposition: `attachment; filename="${headerFileName(name)}"`,
    },
    customMetadata: omitEmpty({
      tag,
      name,
      sha256,
      size: size ? String(size) : "",
      uploadedAt: String(uploadedAt || Date.now()),
    }),
  };
}

function normalizeUploadedParts(rawParts) {
  if (!Array.isArray(rawParts) || rawParts.length === 0) {
    throw httpError(400, "parts are required");
  }
  return rawParts
    .map((part) => {
      const partNumber = Number(part?.partNumber);
      const etag = stringValue(part?.etag);
      if (!Number.isInteger(partNumber) || partNumber < 1 || !etag) {
        throw httpError(400, "each part needs partNumber and etag");
      }
      return { partNumber, etag };
    })
    .sort((left, right) => left.partNumber - right.partNumber);
}

function normalizePath(pathname) {
  if (!pathname || pathname === "/") return "/";
  return pathname.replace(/\/+$/, "");
}

function normalizeTag(raw) {
  return stringValue(raw).replace(/^refs\/tags\//, "").trim();
}

function normalizeVersion(raw) {
  return stringValue(raw)
    .replace(/^refs\/tags\//, "")
    .replace(/^[vV]/, "")
    .split("+")[0]
    .trim();
}

function normalizeTrack(raw) {
  const value = stringValue(raw).toLowerCase();
  if (value === "stable") return "stable";
  if (value === "beta" || value === "prerelease" || value === "pre-release") return "beta";
  return "";
}

function classifyReleaseTrack(version, prerelease) {
  if (prerelease) return "beta";
  const parts = normalizeVersion(version).split(".");
  if (parts.length === 3 && parts.every(isDigits)) return "stable";
  if (parts.length === 4 && parts.every(isDigits)) return "beta";
  return "unsupported";
}

function compareVersions(leftRaw, rightRaw) {
  const left = normalizeVersion(leftRaw);
  const right = normalizeVersion(rightRaw);
  if (left === right) return 0;

  const leftParts = numericParts(left);
  const rightParts = numericParts(right);
  if (leftParts && rightParts) {
    const length = Math.max(leftParts.length, rightParts.length);
    for (let index = 0; index < length; index += 1) {
      const leftValue = leftParts[index] || 0;
      const rightValue = rightParts[index] || 0;
      if (leftValue !== rightValue) {
        return leftValue > rightValue ? 1 : -1;
      }
    }
    return 0;
  }

  return left.localeCompare(right);
}

function numericParts(version) {
  if (!version) return null;
  const parts = version.split(".");
  if (!parts.every(isDigits)) return null;
  return parts.map((part) => Number(part));
}

function isDigits(value) {
  return /^\d+$/.test(value);
}

function isEditionApkAsset(name, edition) {
  return name.toLowerCase().endsWith(`-${edition}.apk`);
}

function isKnownEditionApkAsset(name) {
  const normalized = name.toLowerCase();
  return normalized.endsWith("-standard.apk") || normalized.endsWith("-omniinfer.apk");
}

function normalizeEdition(raw) {
  const value = stringValue(raw).toLowerCase();
  return value === "standard" ? "standard" : "omniinfer";
}

function normalizeSource(raw) {
  return stringValue(raw).toLowerCase() === "github" ? "github" : "worker";
}

function parseBoolean(raw) {
  const value = stringValue(raw).toLowerCase();
  return value === "1" || value === "true" || value === "yes";
}

function normalizeTimestamp(raw) {
  if (typeof raw === "number" && Number.isFinite(raw)) {
    return raw < 10_000_000_000 ? Math.trunc(raw * 1000) : Math.trunc(raw);
  }
  const value = stringValue(raw);
  if (!value) return 0;
  if (/^\d+$/.test(value)) {
    const numeric = Number(value);
    return numeric < 10_000_000_000 ? numeric * 1000 : numeric;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function emptyUpdateResponse({ currentVersion, checkedAt, edition, source }) {
  return {
    ok: true,
    currentVersion,
    latestVersion: currentVersion,
    hasUpdate: false,
    checkedAt,
    publishedAt: 0,
    tag: "",
    track: "",
    releaseUrl: "",
    releaseNotes: "",
    apkName: "",
    apkDownloadUrl: "",
    edition,
    source,
    assets: [],
  };
}

function stringValue(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

function normalizeSize(raw) {
  const size = Number(raw);
  return Number.isFinite(size) && size > 0 ? Math.trunc(size) : 0;
}

function omitEmpty(input) {
  return Object.fromEntries(Object.entries(input).filter(([, value]) => stringValue(value) !== ""));
}

function encodePathSegment(raw) {
  return encodeURIComponent(stringValue(raw));
}

function decodePathSegment(raw) {
  try {
    return decodeURIComponent(raw);
  } catch {
    throw httpError(400, "invalid encoded path segment");
  }
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw httpError(400, "Invalid JSON body");
  }
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: JSON_HEADERS,
  });
}
