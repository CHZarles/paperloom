from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path

from .audit import audit_dataset
from .dataset import load_dataset
from .conversation import ConversationState
from .golden_fixture import GoldenFixtureHarness
from .judge import LLMJudge, evaluate_calibration, load_calibration_cases
from .live_chat import LiveResearchChatHarness
from .llm import MiniMaxChatModel
from .product_db_dataset import DockerMySqlProductCorpusStore, summarize_product_corpus
from .provider_config import DockerMySqlProviderConfigStore, EnvProviderConfigStore
from .scoring import BehaviorScorer
from .service import serve


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the Python research harness prototype.")
    parser.add_argument("--manifest", default="research/golden-data/manifest.yaml")
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("validate", help="Load the dataset and validate emitted traces in memory.")
    audit_parser = subcommands.add_parser("audit", help="Verify authored anchors against parsed reading models.")
    audit_parser.add_argument(
        "--out",
        default="data/golden/transformer-bert-gpt/generated-audit/anchor-verification.json",
    )
    run_parser = subcommands.add_parser("run", help="Run all cases and write frontend-readable JSON artifacts.")
    run_parser.add_argument("--out", default="eval/rag/runs/python-harness-prototype")
    agent_parser = subcommands.add_parser("agent-run", help="Run the real tool-using agent harness with MiniMax.")
    agent_parser.add_argument("--out", default="eval/rag/runs/python-minimax-agent")
    agent_parser.add_argument("--case-id", action="append", default=[])
    agent_parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    agent_parser.add_argument("--max-tokens", type=int, default=3000)
    chat_parser = subcommands.add_parser("chat", help="Chat with current product DB papers.")
    chat_parser.add_argument("--question", required=True)
    chat_parser.add_argument("--paper-id", action="append", default=[])
    chat_parser.add_argument("--paper-query", default="")
    chat_parser.add_argument("--limit", type=int, default=30)
    chat_parser.add_argument("--out", default="")
    chat_parser.add_argument("--state", default="")
    chat_parser.add_argument("--state-out", default="")
    chat_parser.add_argument("--conversation-id", default="live_conversation")
    chat_parser.add_argument("--print-state", action="store_true")
    chat_parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    chat_parser.add_argument("--max-tokens", type=int, default=3000)
    chat_parser.add_argument("--print-run", action="store_true")
    shell_parser = subcommands.add_parser("chat-shell", help="Open an interactive terminal chat with product DB papers.")
    shell_parser.add_argument("--paper-id", action="append", default=[])
    shell_parser.add_argument("--paper-query", default="")
    shell_parser.add_argument("--limit", type=int, default=30)
    shell_parser.add_argument("--out", default="")
    shell_parser.add_argument("--state", default="")
    shell_parser.add_argument("--state-out", default="")
    shell_parser.add_argument("--conversation-id", default="live_conversation")
    shell_parser.add_argument("--print-run", action="store_true")
    shell_parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    shell_parser.add_argument("--max-tokens", type=int, default=3000)
    serve_parser = subcommands.add_parser("serve", help="Run the internal HTTP research harness service.")
    serve_parser.add_argument("--host", default="127.0.0.1")
    serve_parser.add_argument("--port", type=int, default=8091)
    serve_parser.add_argument("--internal-token", default="")
    serve_parser.add_argument("--max-tokens", type=int, default=3000)
    serve_parser.add_argument("--corpus-limit", type=int, default=1000)
    judge_parser = subcommands.add_parser(
        "judge-calibrate",
        help="Compare one LLM judge with fixed human-labelled harness runs.",
    )
    judge_parser.add_argument("--labels", default="research/golden-data/human-labels.yaml")
    judge_parser.add_argument("--out", default="eval/rag/runs/llm-judge-calibration")
    judge_parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    judge_parser.add_argument("--max-tokens", type=int, default=1200)
    args = parser.parse_args(argv)

    if args.command == "validate":
        dataset = load_dataset(args.manifest)
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]
        report = BehaviorScorer().score_dataset(dataset, runs)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "audit":
        report = audit_dataset(load_dataset(args.manifest))
        target = Path(args.out)
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "run":
        dataset = load_dataset(args.manifest)
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]
        report = BehaviorScorer().score_dataset(dataset, runs)
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        for run in runs:
            case_dir = out / str(run["case_id"])
            case_dir.mkdir(parents=True, exist_ok=True)
            _write_json(case_dir / "harness_run.json", run)
            _write_child_artifacts(case_dir, run)
        _write_json(out / "score_report.json", report)
        print(json.dumps({"out": str(out), "score_report": report}, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "agent-run":
        dataset = load_dataset(args.manifest)
        selected = set(args.case_id)
        cases_by_id = {str(case.get("id")): case for case in dataset.cases}
        unknown_case_ids = sorted(selected - set(cases_by_id))
        if unknown_case_ids:
            print(json.dumps({
                "error": "unknown_case_id",
                "case_ids": unknown_case_ids,
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        cases = [case for case in dataset.cases if not selected or case.get("id") in selected]
        provider, model = _live_model(args.provider_source)
        harness = LiveResearchChatHarness(model, max_completion_tokens=args.max_tokens)
        runs = [harness.run_case(dataset, case) for case in cases]
        report = BehaviorScorer().score_dataset(
            dataset if not selected else _dataset_with_cases(dataset, cases),
            runs,
        )
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        for run in runs:
            case_dir = out / str(run["case_id"])
            case_dir.mkdir(parents=True, exist_ok=True)
            _write_json(case_dir / "harness_run.json", run)
            _write_child_artifacts(case_dir, run)
        _write_json(out / "score_report.json", report)
        print(json.dumps({
            "out": str(out),
            "provider": provider.public_diagnostics(),
            "score_report": report,
        }, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "chat":
        dataset = DockerMySqlProductCorpusStore().load_dataset(
            paper_ids=args.paper_id or _state_scope_paper_ids(args.state),
            query=args.paper_query,
            limit=args.limit,
        )
        provider, model = _live_model(args.provider_source)
        harness = LiveResearchChatHarness(model, max_completion_tokens=args.max_tokens)
        state = _chat_state(args, dataset)
        run, state = harness.run_turn(dataset, state, args.question)
        out_value = None
        if args.out:
            out = Path(args.out)
            case_dir = out / str(run["case_id"])
            case_dir.mkdir(parents=True, exist_ok=True)
            _write_json(case_dir / "harness_run.json", run)
            _write_child_artifacts(case_dir, run)
            _write_json(case_dir / "conversation_state.json", state.to_dict())
            out_value = str(case_dir)
        state_path = args.state_out or args.state
        if state_path:
            state.save(state_path)
        response = {
            "provider": provider.public_diagnostics(),
            "corpus": summarize_product_corpus(dataset).to_dict(),
            "answer": run["research_answer"],
            "conversation": {
                "conversation_id": state.conversation_id,
                "turn_index": state.turn_index,
                "scope_paper_ids": state.scope_paper_ids,
                "selected_paper_ids": state.selected_paper_ids,
                "selected_evidence_ids": state.selected_evidence_ids,
                "state_path": state_path or None,
            },
            "diagnostics": {
                "finish_reason": run["diagnostics"].get("finish_reason"),
                "tool_call_count": run["diagnostics"].get("tool_call_count"),
            },
        }
        if out_value:
            response["out"] = out_value
        if args.print_run:
            response["harness_run"] = run
        if args.print_state:
            response["conversation_state"] = state.to_dict()
        print(json.dumps(response, indent=2, sort_keys=True, ensure_ascii=False))
        return 0
    if args.command == "chat-shell":
        dataset = DockerMySqlProductCorpusStore().load_dataset(
            paper_ids=args.paper_id or _state_scope_paper_ids(args.state),
            query=args.paper_query,
            limit=args.limit,
        )
        provider, model = _live_model(args.provider_source)
        harness = LiveResearchChatHarness(model, max_completion_tokens=args.max_tokens)
        state = _chat_state(args, dataset)
        state_path = args.state_out or args.state
        print(json.dumps({
            "provider": provider.public_diagnostics(),
            "corpus": summarize_product_corpus(dataset).to_dict(),
            "conversation_id": state.conversation_id,
            "state_path": state_path or None,
        }, indent=2, sort_keys=True, ensure_ascii=False))
        run_chat_shell(
            dataset,
            harness,
            state,
            state_path=state_path,
            out=args.out,
            print_run=args.print_run,
        )
        return 0
    if args.command == "serve":
        serve(
            host=args.host,
            port=args.port,
            internal_token=args.internal_token,
            max_completion_tokens=args.max_tokens,
            corpus_limit=args.corpus_limit,
        )
        return 0
    if args.command == "judge-calibrate":
        try:
            calibration = load_calibration_cases(args.labels)
            provider, model = _judge_model(args.provider_source)
        except Exception as error:
            print(json.dumps({
                "error": "judge_calibration_setup_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        report = evaluate_calibration(
            calibration,
            LLMJudge(model, max_tokens=args.max_tokens),
            judge_metadata=provider.public_diagnostics(),
        )
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        report_path = out / "agreement_report.json"
        _write_json(report_path, report)
        print(json.dumps({
            "out": str(out),
            "agreement_report": report,
        }, indent=2, sort_keys=True, ensure_ascii=False))
        if report["technical_error_count"]:
            return 2
        return 0 if report["accepted"] else 1
    return 2


def run_chat_shell(
    dataset,
    harness,
    state: ConversationState,
    state_path: str = "",
    out: str = "",
    print_run: bool = False,
    input_func=input,
    output_func=print,
) -> ConversationState:
    output_func("Interactive chat ready. Type /help for commands, /exit to quit.")
    while True:
        try:
            user_message = input_func("you> ")
        except EOFError:
            output_func("")
            break
        stripped = user_message.strip()
        if not stripped:
            continue
        if stripped in {"/exit", "/quit"}:
            break
        if stripped == "/help":
            output_func(
                "Commands: /state, /history, /new, /clear, /save, /exit. "
                "Any other input is sent as a research turn."
            )
            continue
        if stripped == "/state":
            output_func(json.dumps(_terminal_state_summary(state), indent=2, sort_keys=True, ensure_ascii=False))
            continue
        if stripped == "/save":
            _save_state_if_requested(state, state_path)
            output_func(f"Saved state: {state_path}" if state_path else "No --state path configured.")
            continue
        if stripped in {"/new", "/clear"}:
            state = state.reset()
            _save_state_if_requested(state, state_path)
            output_func("Started a fresh conversation context.")
            continue
        if stripped == "/history":
            output_func(json.dumps(state.message_history, indent=2, ensure_ascii=False))
            continue

        run, state = harness.run_turn(dataset, state, stripped)
        case_dir = _write_run_if_requested(run, state, out)
        _save_state_if_requested(state, state_path)
        _print_terminal_answer(output_func, run, case_dir)
        if print_run:
            output_func(json.dumps(run, indent=2, sort_keys=True, ensure_ascii=False))

    _save_state_if_requested(state, state_path)
    return state


def _print_terminal_answer(output_func, run: dict, case_dir: Path | None) -> None:
    answer = run.get("research_answer") or {}
    output_func("")
    output_func("assistant")
    text = answer.get("markdown") or answer.get("summary") or ""
    if text:
        output_func(str(text))
    if case_dir:
        output_func(f"artifacts: {case_dir}")
    output_func("")


def _write_run_if_requested(run: dict, state: ConversationState, out: str) -> Path | None:
    if not out:
        return None
    case_dir = Path(out) / str(run["case_id"])
    case_dir.mkdir(parents=True, exist_ok=True)
    _write_json(case_dir / "harness_run.json", run)
    _write_child_artifacts(case_dir, run)
    _write_json(case_dir / "conversation_state.json", state.to_dict())
    return case_dir


def _save_state_if_requested(state: ConversationState, state_path: str) -> None:
    if state_path:
        state.save(state_path)


def _terminal_state_summary(state: ConversationState) -> dict:
    return {
        "conversation_id": state.conversation_id,
        "turn_index": state.turn_index,
        "scope_paper_ids": state.scope_paper_ids,
        "selected_paper_ids": state.selected_paper_ids,
        "selected_evidence_ids": state.selected_evidence_ids,
        "message_count": len(state.message_history),
        "tool_trace_count": len(state.tool_traces),
    }


def _live_model(provider_source: str):
    store = DockerMySqlProviderConfigStore() if provider_source == "db" else EnvProviderConfigStore()
    provider = store.load_active_provider("llm")
    return provider, MiniMaxChatModel(provider, temperature=0.0, top_p=1.0)


def _judge_model(provider_source: str):
    store = DockerMySqlProviderConfigStore() if provider_source == "db" else EnvProviderConfigStore()
    provider = store.load_active_provider("llm")
    return provider, MiniMaxChatModel(provider, temperature=0.0, top_p=1.0)


def _chat_state(args, dataset):
    if args.state and Path(args.state).exists():
        state = ConversationState.load(args.state)
    else:
        state = ConversationState.new(args.conversation_id)
    if args.paper_id:
        return replace(state, scope_paper_ids=args.paper_id)
    if not state.scope_paper_ids:
        return replace(state, scope_paper_ids=sorted(dataset.paper_records_by_id))
    return state


def _state_scope_paper_ids(path: str) -> list[str]:
    if not path or not Path(path).exists():
        return []
    return ConversationState.load(path).scope_paper_ids


def _dataset_with_cases(dataset, cases):
    return replace(dataset, cases=cases)


def _write_child_artifacts(case_dir: Path, run: dict) -> None:
    for field in (
        "evidence_ledger",
        "citation_validation",
        "research_answer",
        "skills_used",
        "react_trace",
        "paper_candidates",
    ):
        value = run.get(field, []) if field in {"skills_used", "react_trace", "paper_candidates"} else run[field]
        _write_json(case_dir / f"{field}.json", value)


def _write_json(path: Path, value: object) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")
