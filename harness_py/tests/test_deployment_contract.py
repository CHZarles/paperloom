from __future__ import annotations

import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]


class DeploymentContractTest(unittest.TestCase):
    def test_qdrant_compose_requires_server_auth_and_binds_host_ports_to_loopback(self) -> None:
        compose = yaml.safe_load(
            (REPO_ROOT / "docs/docker-compose.yaml").read_text(encoding="utf-8")
        )
        qdrant = compose["services"]["qdrant"]

        self.assertEqual(
            "${QDRANT_API_KEY:?Set QDRANT_API_KEY in .env}",
            qdrant["environment"]["QDRANT__SERVICE__API_KEY"],
        )
        self.assertTrue(qdrant["ports"])
        self.assertTrue(all(str(port).startswith("127.0.0.1:") for port in qdrant["ports"]))


if __name__ == "__main__":
    unittest.main()
