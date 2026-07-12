from __future__ import annotations

import base64
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


@dataclass(frozen=True)
class ProviderConfig:
    scope: str
    provider: str
    api_style: str
    api_base_url: str
    model: str
    api_key: str

    def public_diagnostics(self) -> dict[str, object]:
        return {
            "scope": self.scope,
            "provider": self.provider,
            "api_style": self.api_style,
            "api_base_url": self.api_base_url,
            "model": self.model,
            "has_api_key": bool(self.api_key),
        }


class ProviderConfigStore:
    def load_active_provider(self, scope: str = "llm") -> ProviderConfig:
        raise NotImplementedError


class EnvProviderConfigStore(ProviderConfigStore):
    def __init__(
        self,
        api_base_url_env: str = "MINIMAX_API_BASE_URL",
        api_key_env: str = "MINIMAX_API_KEY",
        model_env: str = "MINIMAX_MODEL",
        env_path: str | Path = ".env",
    ):
        self.api_base_url_env = api_base_url_env
        self.api_key_env = api_key_env
        self.model_env = model_env
        self.env_path = Path(env_path)

    def load_active_provider(self, scope: str = "llm") -> ProviderConfig:
        file_env = _read_env_file(self.env_path)
        api_base_url = _env_value(self.api_base_url_env, file_env)
        api_key = _env_value(self.api_key_env, file_env)
        model = _env_value(self.model_env, file_env)
        if not api_base_url or not api_key or not model:
            missing = [
                name
                for name, value in [
                    (self.api_base_url_env, api_base_url),
                    (self.api_key_env, api_key),
                    (self.model_env, model),
                ]
                if not value
            ]
            raise RuntimeError(f"missing environment provider config: {', '.join(missing)}")
        return ProviderConfig(
            scope=scope,
            provider="minimax",
            api_style="openai-compatible",
            api_base_url=normalize_openai_base_url(api_base_url),
            model=model,
            api_key=api_key,
        )


class DockerMySqlProviderConfigStore(ProviderConfigStore):
    """Loads active provider config from the local product DB via the MySQL container.

    This keeps the Python prototype dependency-free while still using the same DB source of truth
    as the Java product. It expects `.env` to provide DB and encryption settings.
    """

    def __init__(
        self,
        env_path: str | Path = ".env",
        container_env: str = "HARNESS_MYSQL_CONTAINER",
        default_container: str = "pai_smart_mysql",
    ):
        self.env_path = Path(env_path)
        self.container = os.getenv(container_env, default_container)
        self.env = _read_env_file(self.env_path)

    def load_active_provider(self, scope: str = "llm") -> ProviderConfig:
        db_name = _database_name(self.env.get("SPRING_DATASOURCE_URL", ""))
        user = self.env.get("SPRING_DATASOURCE_USERNAME", "root")
        password = self.env.get("SPRING_DATASOURCE_PASSWORD", "")
        rows = self._query_provider_rows(db_name, user, password, scope)
        active = next((row for row in rows if _truthy(row["active"])), None)
        if active is None:
            raise RuntimeError(f"no active provider row found for scope={scope}")
        api_key = decrypt_provider_key(active["api_key_ciphertext"], self._secret_key())
        if not api_key and scope == "embedding" and active["provider_code"] == "minimax":
            shared = next((row for row in self._query_provider_rows(db_name, user, password, "llm")
                           if row["provider_code"] == "minimax"), None)
            api_key = decrypt_provider_key(shared["api_key_ciphertext"], self._secret_key()) if shared else ""
        if not api_key:
            raise RuntimeError(f"active provider {active['provider_code']} for scope={scope} has no API key")
        return ProviderConfig(
            scope=scope,
            provider=active["provider_code"],
            api_style=active["api_style"],
            api_base_url=normalize_openai_base_url(active["api_base_url"]),
            model=active["model_name"],
            api_key=api_key,
        )

    def _query_provider_rows(self, db_name: str, user: str, password: str, scope: str) -> list[dict[str, str]]:
        sql = (
            "select config_scope,provider_code,api_style,api_base_url,model_name,"
            "ifnull(api_key_ciphertext,''),enabled,active "
            "from model_provider_configs where config_scope=%s order by provider_code"
        )
        command = [
            "docker",
            "exec",
            "-e",
            f"MYSQL_PWD={password}",
            self.container,
            "/usr/bin/mysql",
            "-N",
            "-B",
            f"-u{user}",
            "-D",
            db_name,
            "-e",
            sql % _mysql_quote(scope),
        ]
        result = subprocess.run(command, check=True, text=True, capture_output=True)
        rows: list[dict[str, str]] = []
        for line in result.stdout.splitlines():
            parts = line.split("\t")
            if len(parts) != 8:
                continue
            rows.append({
                "config_scope": parts[0],
                "provider_code": parts[1],
                "api_style": parts[2],
                "api_base_url": parts[3],
                "model_name": parts[4],
                "api_key_ciphertext": "" if parts[5] == "NULL" else parts[5],
                "enabled": parts[6],
                "active": parts[7],
            })
        return rows

    def _secret_key(self) -> str:
        key = self.env.get("MODEL_PROVIDER_SECURITY_SECRET_KEY") or self.env.get("JWT_SECRET_KEY")
        if not key:
            raise RuntimeError("MODEL_PROVIDER_SECURITY_SECRET_KEY or JWT_SECRET_KEY is required to decrypt provider keys")
        return key


def decrypt_provider_key(ciphertext: str | None, base64_secret: str) -> str:
    if not ciphertext:
        return ""
    iv_raw, encrypted_raw = ciphertext.split(":", 1)
    key = base64.b64decode(base64_secret)
    iv = base64.b64decode(iv_raw)
    encrypted = base64.b64decode(encrypted_raw)
    return AESGCM(key).decrypt(iv, encrypted, None).decode("utf-8")


def normalize_openai_base_url(raw: str) -> str:
    value = raw.rstrip("/")
    if value.endswith("/chat/completions"):
        value = value[: -len("/chat/completions")]
    return value.rstrip("/")


def _read_env_file(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def _database_name(jdbc_url: str) -> str:
    match = re.search(r"jdbc:mysql://[^/]+/([^?]+)", jdbc_url)
    if match:
        return match.group(1)
    parsed = urlparse(jdbc_url.replace("jdbc:", "", 1))
    return parsed.path.lstrip("/") or "paismart"


def _mysql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def _truthy(value: str) -> bool:
    return value in {"1", "\x01", "true", "TRUE", "t"}


def _env_value(name: str, file_env: dict[str, str]) -> str:
    return (os.getenv(name) or file_env.get(name) or "").strip()
