"""Python prototype for the evidence-first research harness."""

from .dataset import load_dataset
from .agent_harness import ResearchAgentHarness
from .harness import ContractDrivenHarness
from .scoring import TraceScorer

__all__ = ["ContractDrivenHarness", "ResearchAgentHarness", "TraceScorer", "load_dataset"]
