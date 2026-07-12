"""Evidence-bounded, paradigm-driven paper research harness."""

from .intent import TurnInterpreter
from .models import (
    Claim,
    ClaimVerdict,
    EvidenceCoverage,
    IntentFrame,
    Obligation,
    ParadigmFrame,
    ResearchState,
    TaskFrame,
    TurnDecision,
    TurnFrame,
)
from .plans import PARADIGM_RECIPES, ParadigmRecipe
from .runtime import EvidenceCollector, ParadigmDrivenHarness

__all__ = [
    "Claim",
    "ClaimVerdict",
    "EvidenceCollector",
    "EvidenceCoverage",
    "IntentFrame",
    "Obligation",
    "PARADIGM_RECIPES",
    "ParadigmDrivenHarness",
    "ParadigmFrame",
    "ParadigmRecipe",
    "ResearchState",
    "TaskFrame",
    "TurnDecision",
    "TurnFrame",
    "TurnInterpreter",
]
