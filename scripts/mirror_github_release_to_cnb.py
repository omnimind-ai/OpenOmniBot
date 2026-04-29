#!/usr/bin/env python3

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, NoReturn, Optional, Set, Tuple
from urllib.parse import unquote, urlparse


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Mirror GitHub-built release assets to a CNB release."
    )
    parser.add_argument("--repo", required=True, help="CNB repo slug, e.g. o.a/OpenOmniBot")
    parser.add_argument("--tag", required=True, help="Release tag to mirror")
    parser.add_argument(
        "--target-commitish",
        required=True,
        help="Target commit SHA or branch name for the CNB release",
    )
    parser.add_argument("--name", required=True, help="CNB release title")
    parser.add_argument("--body", default="", help="CNB release body")
    parser.add_argument(
        "--make-latest",
        default="false",
        choices=("true", "false", "legacy"),
        help="Value passed to cnb releases post-release --make-latest",
    )
    parser.add_argument("--draft", action="store_true", help="Create the CNB release as draft")
    parser.add_argument(
        "--prerelease",
        action="store_true",
        help="Create the CNB release as prerelease",
    )
    parser.add_argument(
        "--asset",
        action="append",
        required=True,
        help="Local asset path to upload. Repeat for multiple files.",
    )
    parser.add_argument(
        "--wait-for-tag-attempts",
        type=int,
        default=30,
        help="How many times to poll CNB for the mirrored tag before failing",
    )
    parser.add_argument(
        "--wait-for-tag-interval",
        type=int,
        default=10,
        help="Seconds to sleep between CNB tag checks",
    )
    return parser.parse_args()


def print_step(message: str) -> None:
    print(message, flush=True)


def fail(message: str, *, stdout: str = "", stderr: str = "") -> NoReturn:
    print(message, file=sys.stderr)
    if stdout.strip():
        print("stdout:", file=sys.stderr)
        print(stdout.strip(), file=sys.stderr)
    if stderr.strip():
        print("stderr:", file=sys.stderr)
        print(stderr.strip(), file=sys.stderr)
    raise SystemExit(1)


def require_tools() -> None:
    missing = [tool for tool in ("cnb", "curl") if shutil.which(tool) is None]
    if missing:
        fail(f"Missing required tools in PATH: {', '.join(missing)}")


def run_command(command: List[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def run_cnb_json(arguments: List[str], expected_statuses: Set[int]) -> Dict[str, Any]:
    command = ["cnb", *arguments, "--verbose"]
    result = run_command(command)
    if result.returncode != 0:
        fail(
            f"CNB CLI command failed: {shlex.join(command)}",
            stdout=result.stdout,
            stderr=result.stderr,
        )

    stdout = result.stdout.strip()
    if not stdout:
        if 204 in expected_statuses:
            return {"status": 204}
        fail(f"CNB CLI returned empty output: {shlex.join(command)}", stderr=result.stderr)

    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        fail(
            f"Failed to parse CNB CLI JSON output: {exc}",
            stdout=result.stdout,
            stderr=result.stderr,
        )

    status = int(payload.get("status", 0) or 0)
    if status not in expected_statuses:
        fail(
            f"Unexpected CNB status {status} for command: {shlex.join(command)}",
            stdout=result.stdout,
            stderr=result.stderr,
        )
    return payload


def wait_for_tag(args: argparse.Namespace) -> None:
    for attempt in range(1, args.wait_for_tag_attempts + 1):
        payload = run_cnb_json(
            ["git", "get-tag", "--repo", args.repo, "--tag", args.tag],
            {200, 404},
        )
        if int(payload["status"]) == 200:
            print_step(f"CNB tag {args.tag} is available.")
            return
        if attempt == args.wait_for_tag_attempts:
            break
        print_step(
            f"Waiting for CNB tag {args.tag} to appear "
            f"({attempt}/{args.wait_for_tag_attempts})..."
        )
        time.sleep(args.wait_for_tag_interval)

    fail(
        f"Timed out waiting for CNB tag {args.tag} in repo {args.repo}. "
        "Make sure the GitHub -> CNB tag sync workflow completed successfully."
    )


def get_existing_release_id(repo: str, tag: str) -> Optional[str]:
    payload = run_cnb_json(
        ["releases", "get-release-by-tag", "--repo", repo, "--tag", tag],
        {200, 404},
    )
    if int(payload["status"]) == 404:
        return None
    release_id = payload.get("data", {}).get("id")
    if not release_id:
        fail(f"CNB returned release metadata for {tag} without an id.")
    return str(release_id)


def delete_release(repo: str, release_id: str) -> None:
    print_step(f"Deleting existing CNB release {release_id}.")
    run_cnb_json(
        ["releases", "delete-release", "--repo", repo, "--release-id", release_id],
        {200, 204},
    )


def create_release(args: argparse.Namespace) -> str:
    command = [
        "releases",
        "post-release",
        "--repo",
        args.repo,
        "--tag-name",
        args.tag,
        "--target-commitish",
        args.target_commitish,
        "--name",
        args.name,
        "--make-latest",
        args.make_latest,
    ]
    if args.body:
        command.extend(["--body", args.body])
    if args.draft:
        command.append("--draft")
    if args.prerelease:
        command.append("--prerelease")

    payload = run_cnb_json(command, {200, 201})
    release_id = payload.get("data", {}).get("id")
    if not release_id:
        fail(f"CNB create release response for {args.tag} did not include release id.")
    print_step(f"Created CNB release {release_id} for tag {args.tag}.")
    return str(release_id)


def parse_verify_url(verify_url: str) -> Tuple[str, str]:
    parsed = urlparse(verify_url)
    segments = [segment for segment in parsed.path.split("/") if segment]
    if len(segments) < 2:
        fail(f"Unexpected verify_url format returned by CNB: {verify_url}")
    upload_token = segments[-2]
    asset_path = unquote(segments[-1])
    return upload_token, asset_path


def upload_asset(repo: str, release_id: str, asset_path: Path) -> None:
    print_step(f"Uploading {asset_path.name} to CNB release {release_id}.")
    request_payload = run_cnb_json(
        [
            "releases",
            "post-release-asset-upload-url",
            "--repo",
            repo,
            "--release-id",
            release_id,
            "--asset-name",
            asset_path.name,
            "--size",
            str(asset_path.stat().st_size),
            "--ttl",
            "1",
            "--overwrite",
        ],
        {200, 201},
    )

    upload_url = request_payload.get("data", {}).get("upload_url")
    verify_url = request_payload.get("data", {}).get("verify_url")
    if not upload_url or not verify_url:
        fail(f"CNB did not return upload_url/verify_url for asset {asset_path.name}.")

    upload_result = run_command(
        [
            "curl",
            "--fail",
            "--silent",
            "--show-error",
            "--location",
            "--request",
            "PUT",
            "--upload-file",
            str(asset_path),
            upload_url,
        ]
    )
    if upload_result.returncode != 0:
        fail(
            f"Failed to upload asset bytes for {asset_path.name}.",
            stdout=upload_result.stdout,
            stderr=upload_result.stderr,
        )

    upload_token, confirmed_asset_path = parse_verify_url(verify_url)
    run_cnb_json(
        [
            "releases",
            "post-release-asset-upload-confirmation",
            "--repo",
            repo,
            "--release-id",
            release_id,
            "--upload-token",
            upload_token,
            "--asset-path",
            confirmed_asset_path,
            "--ttl",
            "0",
        ],
        {200, 201, 204},
    )
    print_step(f"Confirmed CNB asset upload for {asset_path.name}.")


def main() -> int:
    args = parse_args()
    require_tools()

    asset_paths = [Path(asset).expanduser().resolve() for asset in args.asset]
    missing_assets = [str(path) for path in asset_paths if not path.is_file()]
    if missing_assets:
        fail(f"Missing asset files: {', '.join(missing_assets)}")

    if not os.environ.get("CNB_TOKEN"):
        print_step(
            "CNB_TOKEN is not set in the environment. "
            "Continuing with any existing CNB CLI credentials."
        )

    wait_for_tag(args)

    existing_release_id = get_existing_release_id(args.repo, args.tag)
    if existing_release_id is not None:
        delete_release(args.repo, existing_release_id)

    release_id = create_release(args)
    for asset_path in asset_paths:
        upload_asset(args.repo, release_id, asset_path)

    print_step(f"CNB release mirror completed for {args.tag}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
