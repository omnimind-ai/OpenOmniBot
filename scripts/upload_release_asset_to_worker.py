#!/usr/bin/env python3
"""Upload a release asset to the app-update Worker.

Large APKs use the Worker's R2 multipart upload endpoints so each request stays
below Cloudflare's Worker request body limit.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union


DEFAULT_PART_SIZE = 50 * 1024 * 1024
DEFAULT_MULTIPART_THRESHOLD = 50 * 1024 * 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker-url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--file", required=True, type=Path)
    parser.add_argument("--content-type", required=True)
    parser.add_argument("--sha256", default="")
    parser.add_argument("--part-size", type=int, default=DEFAULT_PART_SIZE)
    parser.add_argument("--multipart-threshold", type=int, default=DEFAULT_MULTIPART_THRESHOLD)
    return parser.parse_args()


def normalize_worker_url(raw: str) -> str:
    base = raw.strip().rstrip("/")
    for suffix in ("/updates", "/admin/releases"):
        if base.endswith(suffix):
            base = base[: -len(suffix)]
    if not base:
        raise ValueError("--worker-url is empty")
    return base


def asset_url(base_url: str, tag: str, asset_name: str, query: Optional[Dict[str, str]] = None) -> str:
    quoted_tag = urllib.parse.quote(tag, safe="")
    quoted_name = urllib.parse.quote(asset_name, safe="")
    url = f"{base_url}/admin/releases/{quoted_tag}/assets/{quoted_name}"
    if query:
        url = f"{url}?{urllib.parse.urlencode(query)}"
    return url


def request_json(
    method: str,
    url: str,
    token: str,
    *,
    body: Optional[bytes] = None,
    headers: Optional[Dict[str, str]] = None,
    retries: int = 3,
) -> Dict:
    request_headers = [
        f"Authorization: Bearer {token}",
        "Accept: application/json",
    ]
    request_headers.extend(f"{name}: {value}" for name, value in (headers or {}).items())
    command = [
        "curl",
        "--show-error",
        "--silent",
        "--location",
        "--request",
        method,
        "--write-out",
        "\n%{http_code}",
    ]
    for header in request_headers:
        command.extend(["--header", header])
    if body is not None:
        command.extend(["--data-binary", "@-"])
    command.append(url)

    last_error: Optional[BaseException] = None
    for attempt in range(1, retries + 1):
        result = subprocess.run(
            command,
            input=body,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        stdout = result.stdout.decode("utf-8", errors="replace")
        stderr = result.stderr.decode("utf-8", errors="replace")
        payload, status = split_curl_response(stdout)

        if result.returncode == 0 and status < 400:
            return json.loads(payload) if payload else {}

        detail = payload or stderr
        last_error = RuntimeError(f"{method} {url} failed with HTTP {status}: {detail}")
        if status and status < 500:
            break
        if result.returncode != 0 and not status:
            last_error = RuntimeError(f"{method} {url} failed: {stderr or payload}")
            if attempt >= retries:
                break

        if attempt < retries:
            time.sleep(2 * attempt)

    raise last_error or RuntimeError(f"{method} {url} failed")


def split_curl_response(stdout: str) -> Tuple[str, int]:
    if "\n" not in stdout:
        return stdout, 0
    payload, status_raw = stdout.rsplit("\n", 1)
    try:
        return payload, int(status_raw)
    except ValueError:
        return stdout, 0


def simple_upload(args: argparse.Namespace, base_url: str, size: int) -> None:
    data = args.file.read_bytes()
    response = request_json(
        "PUT",
        asset_url(base_url, args.tag, args.file.name),
        args.token,
        body=data,
        headers={
            "Content-Type": args.content_type,
            "X-Content-SHA256": args.sha256,
            "Content-Length": str(size),
        },
    )
    print(f"Uploaded {args.file.name} via single PUT: {response.get('asset', {}).get('downloadUrl', '')}")


def multipart_upload(args: argparse.Namespace, base_url: str, size: int) -> None:
    create_response = request_json(
        "POST",
        asset_url(base_url, args.tag, args.file.name, {"action": "mpu-create"}),
        args.token,
        body=b"",
        headers={
            "Content-Type": args.content_type,
            "X-Content-SHA256": args.sha256,
            "X-Content-Size": str(size),
        },
    )
    upload_id = create_response.get("upload", {}).get("uploadId")
    if not upload_id:
        raise RuntimeError(f"Worker did not return a multipart uploadId: {create_response}")

    parts: List[Dict[str, Union[str, int]]] = []
    try:
        with args.file.open("rb") as file:
            part_number = 1
            while True:
                chunk = file.read(args.part_size)
                if not chunk:
                    break
                part_response = request_json(
                    "PUT",
                    asset_url(
                        base_url,
                        args.tag,
                        args.file.name,
                        {
                            "action": "mpu-uploadpart",
                            "uploadId": upload_id,
                            "partNumber": str(part_number),
                        },
                    ),
                    args.token,
                    body=chunk,
                    headers={
                        "Content-Type": "application/octet-stream",
                        "Content-Length": str(len(chunk)),
                    },
                )
                part = part_response.get("part")
                if not part:
                    raise RuntimeError(f"Worker did not return uploaded part metadata: {part_response}")
                parts.append({"partNumber": int(part["partNumber"]), "etag": str(part["etag"])})
                print(f"Uploaded {args.file.name} part {part_number} ({len(chunk)} bytes)")
                part_number += 1

        complete_response = request_json(
            "POST",
            asset_url(base_url, args.tag, args.file.name, {"action": "mpu-complete", "uploadId": upload_id}),
            args.token,
            body=json.dumps({"parts": parts, "sha256": args.sha256, "size": size}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        print(f"Uploaded {args.file.name} via multipart: {complete_response.get('asset', {}).get('downloadUrl', '')}")
    except Exception:
        try:
            request_json(
                "DELETE",
                asset_url(base_url, args.tag, args.file.name, {"action": "mpu-abort", "uploadId": upload_id}),
                args.token,
            )
        except Exception as abort_error:
            print(f"Warning: failed to abort multipart upload {upload_id}: {abort_error}", file=sys.stderr)
        raise


def main() -> int:
    args = parse_args()
    if args.part_size <= 0:
        raise ValueError("--part-size must be positive")
    if not args.file.is_file():
        raise FileNotFoundError(args.file)

    base_url = normalize_worker_url(args.worker_url)
    size = args.file.stat().st_size
    if size > args.multipart_threshold:
        multipart_upload(args, base_url, size)
    else:
        simple_upload(args, base_url, size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
