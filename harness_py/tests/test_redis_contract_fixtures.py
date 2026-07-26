from __future__ import annotations

import json
from pathlib import Path


CONTRACT_DIR = Path(__file__).resolve().parents[2] / "docs" / "contracts" / "research-harness"


def load_fixture(name: str) -> dict:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


def test_job_fixture_retry_context_is_compatible_with_worker_contract():
    job = load_fixture("job-v1.json")
    payload = json.loads(job["payload_json"])
    retry = payload["retry"]

    assert job["schema_version"] == "research-harness-job/v1"
    assert job["generation_id"] == payload["request_id"]
    assert payload["scope"]["paper_ids"]
    assert retry["kind"] == "USER_UNSATISFIED"
    assert retry["target_revision"] > 1
    assert retry["previous_cited_evidence_ids"]


def test_event_fixture_terminal_payload_matches_service_result_shape():
    event = load_fixture("event-v1.json")
    payload = json.loads(event["payload_json"])

    assert event["schema_version"] == "research-harness-event/v1"
    assert event["type"] == "result"
    assert event["generation_id"] == payload["request_id"]
    assert payload["answer"]["markdown"]
    assert payload["usage"]["total_tokens"] > 0
