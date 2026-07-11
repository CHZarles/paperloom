"""Python prototype for the evidence-first research harness."""

from .agent_harness import ResearchAgentHarness
from .dataset import load_dataset
from .golden_fixture import GoldenFixtureHarness
from .scoring import BehaviorScorer

__all__ = ["BehaviorScorer", "GoldenFixtureHarness", "ResearchAgentHarness", "load_dataset"]
