from __future__ import annotations

import unittest

from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.retrieval import evaluate_product_retrieval
from harness_py.evaluation.scoring import _trace_metrics


class _Reader:
    def __init__(self, locations: list[dict]):
        self.locations = locations

    def search_locations(self, arguments: dict) -> dict:
        return {
            "locations": self.locations,
            "matched_count": len(self.locations),
            "returned_count": len(self.locations),
            "index_version": "test-index",
        }


class RetrievalEvaluationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = load_dataset("research/golden-data/manifest.yaml")

    def test_scores_location_recall_and_claim_completion_by_rank(self) -> None:
        claim = dict(self.dataset.claims_by_id[
            "adam_default_and_transformer_beta2_are_not_conflicting"
        ])
        requirements = claim["required_evidence"]
        first_ref = requirements[0]["accepted_locations"][0]
        second_ref = requirements[1]["accepted_locations"][0]
        reader = _Reader([
            {"paper_id": requirements[0]["paper_id"], "location_ref": first_ref},
            {"paper_id": requirements[1]["paper_id"], "location_ref": "irrelevant"},
            {"paper_id": requirements[1]["paper_id"], "location_ref": second_ref},
        ])

        report = evaluate_product_retrieval(
            self.dataset,
            [claim],
            lambda ignored: reader,
        )

        probe = report["probes"][0]
        self.assertEqual(0.5, probe["location_recall_at_k"]["1"])
        self.assertEqual(1.0, probe["location_recall_at_k"]["3"])
        self.assertFalse(probe["claim_complete_at_k"]["1"])
        self.assertTrue(probe["claim_complete_at_k"]["3"])
        self.assertAlmostEqual((1.0 + 1.0 / 3.0) / 2.0, probe["mean_reciprocal_rank"])

    def test_paper_discovery_does_not_count_as_location_candidate_recall(self) -> None:
        claim = self.dataset.claims_by_id["transformer_adam_hyperparameters"]
        case = self.dataset.cases[0]
        run = {
            "paper_candidates": [{"paper_id": "attention_is_all_you_need_2017"}],
            "react_trace": [],
            "evidence_ledger": {"items": []},
        }

        metrics = _trace_metrics(case, [claim], run, [])

        self.assertEqual(1.0, metrics["paper_discovery_recall"])
        self.assertEqual(0.0, metrics["candidate_recall"])
        self.assertEqual(0, metrics["candidate_count"])

    def test_stable_claims_have_authored_retrieval_queries(self) -> None:
        self.assertTrue(all(
            self.dataset.retrieval_queries_by_claim_id.get(claim_id)
            for claim_id in self.dataset.claims_by_id
        ))


if __name__ == "__main__":
    unittest.main()
