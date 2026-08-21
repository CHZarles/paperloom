from __future__ import annotations

import unittest

from harness_py.orchestration.run_output import _render_citations


class RunOutputTest(unittest.TestCase):
    def test_display_math_citation_stays_outside_katex_delimiter(self) -> None:
        rendered = _render_citations(
            "$$\nx = 1\n$$ [[source_quote_1]]",
            ["source_quote_1"],
            {"source_quote_1": {"title": "Paper"}},
        )

        self.assertIn("$$\n\n[1]", rendered)
        self.assertNotIn("$$ [1]", rendered)


if __name__ == "__main__":
    unittest.main()
