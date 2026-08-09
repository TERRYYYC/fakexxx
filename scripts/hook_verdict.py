import argparse
import json
import sys
from dataclasses import dataclass
from typing import Any, Dict, List

_MISSING = object()


@dataclass(frozen=True)
class FieldVerdict:
    path: str
    expected: Any
    observed: Any
    status: str
    reason: str


@dataclass(frozen=True)
class Verdict:
    fields: List[FieldVerdict]
    summary: Dict[str, Any]
    passed: bool


def evaluate(expected, report, session_id, restored, control_report=None, control_paths=()):
    fields = []
    for path in sorted(expected):
        expected_value = expected[path]
        observed = _read_path(report, path)
        if observed is _MISSING:
            fields.append(
                FieldVerdict(path, expected_value, _MISSING, "FAILED", "missing")
            )
        elif _is_matcher(expected_value):
            matched, reason = _evaluate_matcher(expected_value, observed)
            fields.append(
                FieldVerdict(
                    path,
                    expected_value,
                    observed,
                    "VERIFIED" if matched else "FAILED",
                    reason,
                )
            )
        elif type(observed) is not type(expected_value):
            fields.append(
                FieldVerdict(
                    path,
                    expected_value,
                    observed,
                    "FAILED",
                    "different_type",
                )
            )
        elif observed != expected_value:
            fields.append(
                FieldVerdict(path, expected_value, observed, "FAILED", "different")
            )
        else:
            fields.append(
                FieldVerdict(path, expected_value, observed, "VERIFIED", "exact")
            )

    primary_fields = tuple(fields)

    if report.get("sessionId") != session_id:
        fields.append(
            FieldVerdict(
                "$sessionId",
                session_id,
                report.get("sessionId", _MISSING),
                "FAILED",
                "stale_session",
            )
        )

    errors = report.get("errors", _MISSING)
    if errors is _MISSING or not isinstance(errors, list) or errors:
        fields.append(
            FieldVerdict("$errors", [], errors, "FAILED", "probe_errors")
        )

    if not restored:
        fields.append(
            FieldVerdict("$restore", True, False, "FAILED", "restore_missing")
        )

    for path in control_paths:
        observed = _read_path(report, path)
        control = _read_path(control_report, path) if control_report is not None else _MISSING
        if observed is _MISSING or control is _MISSING:
            fields.append(
                FieldVerdict(path + "$negativeControl", control, observed, "FAILED", "missing_control")
            )
        elif observed == control:
            fields.append(
                FieldVerdict(path + "$negativeControl", control, observed, "FAILED", "same_as_control")
            )
        else:
            fields.append(
                FieldVerdict(path + "$negativeControl", control, observed, "VERIFIED", "different_from_control")
            )

    verified = sum(1 for item in primary_fields if item.status == "VERIFIED")
    failed = sum(1 for item in primary_fields if item.status == "FAILED")
    summary = {
        "configured": len(expected),
        "verified": verified,
        "failed": failed,
        "restored": bool(restored),
    }
    return Verdict(
        fields=fields,
        summary=summary,
        passed=not any(item.status == "FAILED" for item in fields),
    )


def main(argv=None, emit=print):
    parser = argparse.ArgumentParser(
        description="Compare a FakeGps hook acceptance report with exact path expectations."
    )
    parser.add_argument("--expected-json", required=True)
    parser.add_argument("--report-file", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--restored", action="store_true")
    parser.add_argument("--control-report-file")
    parser.add_argument("--control-path", action="append", default=[])
    try:
        args = parser.parse_args(argv)
        expected = json.loads(args.expected_json)
        if not isinstance(expected, dict):
            raise ValueError("expected JSON must be an object")
        with open(args.report_file, encoding="utf-8") as report_file:
            report = json.load(report_file)
        if not isinstance(report, dict):
            raise ValueError("report JSON must be an object")
        control_report = None
        if args.control_report_file:
            with open(args.control_report_file, encoding="utf-8") as control_file:
                control_report = json.load(control_file)
            if not isinstance(control_report, dict):
                raise ValueError("control report JSON must be an object")
        if args.control_path and control_report is None:
            raise ValueError("--control-path requires --control-report-file")
        verdict = evaluate(
            expected=expected,
            report=report,
            session_id=args.session_id,
            restored=args.restored,
            control_report=control_report,
            control_paths=args.control_path,
        )
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        emit("HARNESS_ERROR {}".format(failure))
        return 2

    for item in verdict.fields:
        line = "{} {} expected={} observed={}".format(
            item.status,
            item.path,
            _format_value(item.expected),
            _format_value(item.observed),
        )
        if item.status == "FAILED":
            line += " reason={}".format(item.reason)
        emit(line)
    emit(json.dumps(verdict.summary, separators=(",", ":")))
    return 0 if verdict.passed else 1


def _read_path(root, path):
    current = root
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            return _MISSING
        current = current[segment]
    return current


def _is_matcher(value):
    return isinstance(value, dict) and "$matcher" in value


def _evaluate_matcher(matcher, observed):
    if matcher.get("$matcher") != "complete_int_set":
        return False, "unknown_matcher"

    allowed = matcher.get("allowed")
    count = matcher.get("count")
    if (
        not isinstance(allowed, list)
        or not allowed
        or any(type(value) is not int for value in allowed)
        or type(count) is not int
        or count <= 0
    ):
        return False, "invalid_matcher"
    if not isinstance(observed, list) or any(
        type(value) is not int for value in observed
    ):
        return False, "different_type"
    if len(observed) != count:
        return False, "wrong_sample_count"

    allowed_values = set(allowed)
    observed_values = set(observed)
    if not observed_values.issubset(allowed_values):
        return False, "unexpected_set"
    if observed_values != allowed_values:
        return False, "incomplete_set"
    return True, "complete_int_set"


def _format_value(value):
    if value is _MISSING:
        return "<missing>"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    sys.exit(main())
