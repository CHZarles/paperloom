"""Public API for the evidence-first research harness."""

from .evaluation.dataset import load_dataset
from .evaluation.golden_fixture import GoldenFixtureHarness
from .evaluation.scoring import BehaviorScorer
from .orchestration.legacy.harness import ResearchAgentHarness
from .orchestration.live_chat import LiveResearchChatHarness
from .orchestration.runtime import HarnessRuntime, LegacyHarnessRuntime

__all__ = [
    "BehaviorScorer",
    "GoldenFixtureHarness",
    "HarnessRuntime",
    "LegacyHarnessRuntime",
    "LiveResearchChatHarness",
    "ResearchAgentHarness",
    "load_dataset",
]
