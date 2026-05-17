"""Optional OpenAI-backed skill smoke test.

This module intentionally uses only the Python standard library. It never reads
or prints API keys by itself; callers pass the key or rely on OPENAI_API_KEY.
"""

from __future__ import annotations

import json
import os
from typing import Any
from urllib import request, error


class OpenAISkillRunner:
    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        timeout: float = 60.0,
    ) -> None:
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY")
        self.base_url = (base_url or os.environ.get("OPENAI_BASE_URL") or "https://api.openai.com/v1").rstrip("/")
        self.model = model or os.environ.get("OPENAI_MODEL") or "gpt-4.1-mini"
        self.timeout = timeout

    def available(self) -> bool:
        return bool(self.api_key)

    def run(self, prompt: str) -> str:
        if not self.api_key:
            raise RuntimeError("OPENAI_API_KEY is not configured")
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": "You are a GUI agent integration tester. Follow the supplied skill and return concise JSON.",
                },
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
        }
        data = json.dumps(payload).encode("utf-8")
        req = request.Request(
            f"{self.base_url}/chat/completions",
            data=data,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout) as resp:
                decoded = json.loads(resp.read().decode("utf-8"))
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"OpenAI HTTP {exc.code}: {body}") from exc
        choice = decoded.get("choices", [{}])[0]
        message = choice.get("message", {})
        content = message.get("content")
        if not isinstance(content, str):
            raise RuntimeError(f"Unexpected OpenAI response: {decoded}")
        return content
