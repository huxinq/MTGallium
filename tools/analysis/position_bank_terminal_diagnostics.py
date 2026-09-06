#!/usr/bin/env python3
"""Private review derivative of officially verified position-bank terminal evidence.

This utility does not replace ResearchRunArtifacts/loadVerifiedRealGamePositionBank
verification. The caller must run the official verifiers first, then supply their
expected identities and --official-verification-completed. No root filtering or
terminal execution is performed. Only Python's standard library is required.
"""

import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import subprocess
import sys


class DiagnosticRefusal(ValueError):
    """Malformed, unverified or population-inconsistent evidence cannot be analyzed."""


REFUSALS = {"RECONSTRUCTION", "CANDIDATE_CAP", "BELIEF", "HYPOTHETICAL_FORK",
            "FIRST_ACTION", "CONTINUATION", "CONTINUATION_LIMIT"}
COUNTS = ("assignedSamples", "attemptedSamples", "terminalSamples", "refusedSamples", "unattemptedSamples")
COORDINATE_KEYS = {"replicate", "particleIndex", "particleSamplingSeed", "futureSeed", "continuationSeed"}
LIMITATIONS = [
    "Review derivative only. Caller-attested official research-run and bank verification remains authority; local hash checks do not replace it.",
    "Means and paired gaps describe sampled belief hypotheses under the declared fixed rollout policies, not observed game results or optimal action values.",
    "All assigned roots are accounted for. Invalid producer roots are retained separately; no partial root is repaired or assigned a payoff summary.",
    "Exact two-sided paired sign tests are available only if every retained terminal payoff in the full input report is -1 or +1. Ties are excluded from the sign count; zero discordance has p=1.",
    "Holm adjustment spans all available unordered candidate-pair tests across every complete root in this full report. This does not adjust for earlier exploration or other reports.",
    "Exploratory diagnostics only: no winner, correct-action label, tactical certificate or playing-strength claim. Repeated roots and shared beliefs do not establish independent real-game outcomes.",
    "Feature equality compares the exact cached after-action feature representation at the same paired coordinate; this hypothetical cache is not the production leaf-settlement distribution.",
]


def require(condition, message):
    if not condition:
        raise DiagnosticRefusal(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def review_binding(result, inputs, source_revision, script_sha256):
    material = {"schemaVersion": 1, "algorithmVersion": "position-bank-terminal-review-v1",
                "analysisSourceRevision": source_revision, "analysisScriptSha256": script_sha256}
    for role, identity_key, input_key in (("terminal", "terminalResearchRunIdentity", "terminalReport"),
                                          ("bank", "bankIdentity", "bankReport")):
        material[role] = {"identity": result[identity_key], "reportSha256": inputs[input_key]["sha256"],
                          "manifestSha256": inputs[input_key]["manifestSha256"]}
    return {"reviewDerivativeIdentity": "position-bank-terminal-review-v1-" + sha256(canonical(material).encode()),
            "analysisBinding": material}


def committed_analysis_revision(repository):
    status = subprocess.check_output(["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=normal",
                                      "--ignore-submodules=none"], text=True)
    require(not status.strip(), "Production review derivatives require committed clean analysis source")
    return subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()


def sign_test(first_higher, second_higher):
    """Exact binomial sign-test calculation; only final probability is floating point."""
    require(type(first_higher) is int and type(second_higher) is int and
            first_higher >= 0 and second_higher >= 0, "Sign counts must be nonnegative integers")
    n = first_higher + second_higher
    if not n:
        return 1.0
    numerator = 2 * sum(math.comb(n, k) for k in range(min(first_higher, second_higher) + 1))
    return float(min(Fraction(1), Fraction(numerator, 2 ** n)))


def holm_adjust(p_values):
    require(all(type(p) in (float, int) and math.isfinite(p) and 0 <= p <= 1 for p in p_values),
            "Holm input must contain finite probabilities")
    adjusted = [0.0] * len(p_values)
    running = 0.0
    for rank, index in enumerate(sorted(range(len(p_values)), key=lambda i: (p_values[i], i))):
        running = max(running, min(1.0, (len(p_values) - rank) * p_values[index]))
        adjusted[index] = running
    return adjusted


def checked_matrix(root, repetitions):
    signatures = [choice["signature"] for choice in root["candidates"]]
    require(signatures and len(set(signatures)) == len(signatures), "Empty or duplicate candidate signatures")
    samples = root.get("samples", [])
    matrix, coordinates = {}, {}
    for sample in samples:
        coordinate = sample["coordinate"]
        require(set(coordinate) == COORDINATE_KEYS, "Unknown or incomplete sample coordinate")
        require(all(type(v) is int for v in coordinate.values()), "Coordinates must contain integers")
        replicate = coordinate["replicate"]
        require(0 <= replicate < repetitions and coordinate["particleIndex"] >= 0, "Coordinate outside assigned population")
        signature = sample["candidateSignature"]
        require(signature in signatures, "Sample candidate is outside the assigned population")
        key = (signature, replicate)
        require(key not in matrix, "Duplicate candidate/replicate sample")
        require(replicate not in coordinates or coordinates[replicate] == coordinate,
                "Paired candidates have mismatched coordinates")
        coordinates[replicate] = coordinate
        payoff, refusal = sample.get("payoff"), sample.get("refusal")
        require((payoff is None) != (refusal is None), "Sample must have exactly a terminal payoff or typed refusal")
        if payoff is None:
            require(refusal in REFUSALS, "Unknown sample refusal type")
            require(all(sample.get(key) is None for key in ("policyDecisions", "rootPolicyDecisions", "opponentPolicyDecisions")),
                    "Refused sample cannot fabricate partial decision audits")
        else:
            require(type(payoff) in (int, float) and math.isfinite(payoff) and -1 <= payoff <= 1,
                    "Invalid terminal payoff")
            audits = [sample.get("rootPolicyDecisions"), sample.get("opponentPolicyDecisions")]
            require(all(isinstance(audit, dict) for audit in audits), "Terminal sample lacks policy audits")
            require(type(sample.get("policyDecisions")) is int and sample["policyDecisions"] >= 0 and
                    sum(audit.get("decisions", 0) for audit in audits) == sample["policyDecisions"] and
                    all(audit.get("evidenceInvalidatingReplacements", 0) == 0 for audit in audits),
                    "Terminal sample has inconsistent or evidence-invalidating policy audits")
        cache = [sample.get("hypotheticalAfterActionFeatures"), sample.get("hypotheticalAfterActionInformationDigest"),
                 sample.get("terminalImmediatelyAfterAction")]
        require(all(value is None for value in cache) or all(value is not None for value in cache),
                "Partial hypothetical after-action cache")
        matrix[key] = sample
    assigned = len(signatures) * repetitions
    terminal = sum(sample.get("payoff") is not None for sample in samples)
    expected = dict(zip(COUNTS, (assigned, len(samples), terminal, len(samples) - terminal, assigned - len(samples))))
    summary = root["summary"]
    require(all(type(summary.get(key)) is int and summary[key] == value for key, value in expected.items()),
            "Producer root sample accounting does not match its matrix")
    require(root.get("refusal") is None or root["refusal"] in REFUSALS, "Unknown root refusal type")
    complete = root.get("refusal") is None and terminal == assigned
    require(type(summary.get("complete")) is bool and summary["complete"] == complete,
            "Producer completeness does not match its full candidate/replicate matrix")
    if not complete:
        require(not summary.get("candidateMeanPayoffs") and not summary.get("pairedGaps"),
                "Invalid producer root contains strategic summaries")
    return signatures, matrix, expected


def analyze(report, bank, terminal_identity, bank_identity, official_verification_completed=False):
    require(official_verification_completed is True, "Official research-run and bank verification must be completed by the caller")
    require(report.get("schemaVersion") == bank.get("schemaVersion") == 1, "Unsupported or missing report schema")
    require(report["researchRunIdentity"] == terminal_identity and bank["bankIdentity"] == bank_identity,
            "Reports do not match caller-verified identities")
    require(report["plan"]["expectedBankIdentity"] == bank_identity, "Terminal report is bound to a different bank")
    require(bank.get("complete") is True, "Bank producer did not complete")
    require(report["plan"]["partition"] == "DEVELOPMENT", "Only development terminal diagnostics are supported")
    repetitions = report["plan"]["samplesPerRoot"]
    require(type(repetitions) is int and repetitions > 0, "Invalid replicate count")
    root_ids = report["plan"]["rootIds"]
    require(root_ids and len(root_ids) == len(set(root_ids)) and [r["rootId"] for r in report["roots"]] == root_ids,
            "Assigned roots are duplicated, missing or reordered")
    bank_roots = {root["rootId"]: root for root in bank["roots"]}
    require(len(bank_roots) == len(bank["roots"]), "Bank has duplicate root IDs")
    validated = []
    totals = Counter({key: 0 for key in COUNTS})
    for root in report["roots"]:
        require(root["rootId"] in bank_roots, "Assigned root is absent from bank")
        original = bank_roots[root["rootId"]]
        require(original["partition"] == "DEVELOPMENT", "Assigned root belongs to validation")
        require(root["candidates"] == original["reconstructedCandidates"], "Candidate population differs from bank reconstruction")
        require(root["actor"] == original["actor"] and root["beliefBaseSeed"] == original["baseSeed"],
                "Root actor or belief seed differs from bank")
        signatures, matrix, counts = checked_matrix(root, repetitions)
        totals.update(counts)
        validated.append((root, original, signatures, matrix))
    require(all(type(report.get(key)) is int and report[key] == totals[key] for key in COUNTS),
            "Report aggregate sample accounting does not match assigned roots")
    complete_count = sum(root["summary"]["complete"] for root in report["roots"])
    require(report["completeRoots"] == complete_count and report["invalidRoots"] == len(root_ids) - complete_count and
            type(report.get("complete")) is bool and report["complete"] == (complete_count == len(root_ids)),
            "Report root completeness accounting is inconsistent")
    all_binary = all(sample.get("payoff") in (-1, 1) for _, _, _, matrix in validated
                     for sample in matrix.values() if sample.get("payoff") is not None)
    roots, invalid_roots, tests = [], [], []
    families = {}
    for root, original, signatures, matrix in validated:
        family = original["decisionFamily"]
        family_counts = families.setdefault(family, {"assignedRoots": 0, "completeRoots": 0, "invalidRoots": 0})
        family_counts["assignedRoots"] += 1
        family_counts["completeRoots" if root["summary"]["complete"] else "invalidRoots"] += 1
        if not root["summary"]["complete"]:
            invalid_roots.append({"rootId": root["rootId"], "decisionFamily": family, "producerSummary": root["summary"],
                                  "rootRefusal": root.get("refusal"), "rootDiagnostic": root.get("diagnostic"),
                                  "sampleRefusals": [sample for sample in matrix.values() if sample.get("refusal") is not None]})
            continue
        means = {signature: math.fsum(matrix[signature, i]["payoff"] for i in range(repetitions)) / repetitions
                 for signature in signatures}
        collision_groups = []
        for i in range(repetitions):
            groups = {}
            for signature in signatures:
                sample = matrix[signature, i]
                if sample.get("hypotheticalAfterActionFeatures") is not None:
                    groups.setdefault(canonical(sample["hypotheticalAfterActionFeatures"]), []).append(sample)
            collision_groups.extend(group for group in groups.values() if len(group) > 1)
        pairs = []
        for first, second in itertools.combinations(signatures, 2):
            paired = [(matrix[first, i], matrix[second, i]) for i in range(repetitions)]
            deltas = [a["payoff"] - b["payoff"] for a, b in paired]
            higher, lower = sum(d > 0 for d in deltas), sum(d < 0 for d in deltas)
            cached = [(a, b) for a, b in paired if a.get("hypotheticalAfterActionFeatures") is not None and
                      b.get("hypotheticalAfterActionFeatures") is not None]
            collisions = [(a, b) for a, b in cached if canonical(a["hypotheticalAfterActionFeatures"]) ==
                          canonical(b["hypotheticalAfterActionFeatures"])]
            pair = {"firstCandidate": first, "secondCandidate": second, "matchedSamples": repetitions,
                    "meanFirstMinusSecond": math.fsum(deltas) / repetitions,
                    "firstHigher": higher, "secondHigher": lower, "equal": repetitions - higher - lower,
                    "discordant": higher + lower,
                    "featureCollisions": {"comparedSamples": len(cached), "missingCacheSamples": repetitions - len(cached),
                                          "equalFeatureSamples": len(collisions),
                                          "differentPayoffSamples": sum(a["payoff"] != b["payoff"] for a, b in collisions),
                                          "replicates": [a["coordinate"]["replicate"] for a, _ in collisions]},
                    "signTest": {"available": all_binary, "twoSidedP": sign_test(higher, lower) if all_binary else None,
                                 "holmAdjustedP": None,
                                 "unavailableReason": None if all_binary else "NON_BINARY_TERMINAL_PAYOFF_IN_REPORT"}}
            pairs.append(pair)
            if all_binary:
                tests.append(pair["signTest"])
        roots.append({"rootId": root["rootId"], "decisionFamily": family, "producerSummary": root["summary"],
                      "candidateMeanPayoffs": means,
                      "featureCollisionGroups": {
                          "groups": len(collision_groups), "samplesInvolved": sum(map(len, collision_groups)),
                          "groupsWithDifferentInformationDigests": sum(len({s.get("hypotheticalAfterActionInformationDigest")
                              for s in group}) > 1 for group in collision_groups),
                          "immediateTerminalSamples": sum(s.get("terminalImmediatelyAfterAction") is True
                              for group in collision_groups for s in group)},
                      "descriptiveCandidateRange": {"minimumMean": min(means.values()), "maximumMean": max(means.values()),
                                                    "range": max(means.values()) - min(means.values())},
                      "pairs": pairs})
    for test, adjusted in zip(tests, holm_adjust([test["twoSidedP"] for test in tests])):
        test["holmAdjustedP"] = adjusted
    return {"schemaVersion": 1, "kind": "position-bank-terminal-review-derivative-v1",
            "officialVerification": "CALLER_ATTESTED_NOT_PERFORMED_BY_THIS_UTILITY",
            "terminalResearchRunIdentity": terminal_identity, "bankIdentity": bank_identity,
            "terminalSourceProvenance": report["sourceProvenance"], "bankSourceProvenance": bank["sourceProvenance"],
            "terminalPlan": report["plan"], "producerComplete": report["complete"],
            "populations": dict(totals, assignedRoots=len(root_ids), completeRoots=complete_count, invalidRoots=len(invalid_roots)),
            "families": families, "signTestFamily": {"binaryPayoffsThroughoutReport": all_binary,
                                                       "holmTests": len(tests), "scope": "all available unordered pairs in all complete assigned roots"},
            "roots": roots, "invalidRoots": invalid_roots, "limitations": LIMITATIONS}


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON object key")
        result[key] = value
    return result


def read_json(data):
    return json.loads(data, object_pairs_hook=reject_duplicate_keys,
                      parse_constant=lambda value: (_ for _ in ()).throw(DiagnosticRefusal("Non-finite JSON number")))


def load_finalized_report(path, identity):
    path = Path(path).resolve(strict=True)
    data = path.read_bytes()
    manifest_path = path.parent / "research-run-manifest.json"
    manifest_data = manifest_path.read_bytes()
    manifest = read_json(manifest_data)
    require(manifest.get("state") == "COMPLETE" and manifest["researchRunIdentity"] == identity,
            "Input lacks a matching finalized research-run manifest")
    entries = [entry for entry in manifest["artifacts"] if entry["relativePath"] == path.name]
    require(len(entries) == 1 and entries[0]["sha256"] == sha256(data) and entries[0]["bytes"] == len(data),
            "Input report is not the artifact recorded by its final manifest")
    return read_json(data), {"path": str(path), "sha256": sha256(data), "bytes": len(data),
                             "manifestPath": str(manifest_path), "manifestSha256": sha256(manifest_data)}


def private_output(path, repository, environment):
    require(Path(path).is_absolute(), "Output paths must be explicit absolute private paths")
    configured = environment.get("MTGALLIUM_PRIVATE_EVIDENCE_ROOT", "").strip()
    require(configured, "MTGALLIUM_PRIVATE_EVIDENCE_ROOT is required; this derivative always stays private")
    private = Path(configured).absolute()
    target = Path(path).absolute()
    require(private == private.resolve() and target == target.resolve(), "Output paths must not traverse symlinks or parent aliases")
    # A worktree can itself be nested inside the public checkout.
    repositories = [p for p in (repository, *repository.parents) if (p / ".git").exists()]
    require(all(not private.is_relative_to(p) and not target.is_relative_to(p) for p in repositories),
            "Private evidence cannot be written inside a source checkout")
    work = private / "search-teacher" / "work"
    require(target != work and target.is_relative_to(work), "Output must be beneath the configured private Search Teacher work root")
    require(not target.exists(), "Review outputs are immutable; choose a new output path")
    return target


def markdown(result):
    p = result["populations"]
    lines = ["# Position-bank terminal review derivative", "",
             f"Review derivative `{result['reviewDerivativeIdentity']}`; analysis source `{result['analysisBinding']['analysisSourceRevision']}`.",
             f"Terminal run `{result['terminalResearchRunIdentity']}`; bank `{result['bankIdentity']}`.",
             f"Roots: {p['assignedRoots']} assigned, {p['completeRoots']} complete, {p['invalidRoots']} invalid. "
             f"Samples: {p['assignedSamples']} assigned, {p['terminalSamples']} terminal, "
             f"{p['refusedSamples']} refused, {p['unattemptedSamples']} unattempted.", "",
             "| Family | Assigned roots | Complete | Invalid |", "| --- | ---: | ---: | ---: |"]
    lines.extend(f"| {name} | {c['assignedRoots']} | {c['completeRoots']} | {c['invalidRoots']} |"
                 for name, c in sorted(result["families"].items()))
    lines.extend(["", f"Holm family: {result['signTestFamily']['holmTests']} tests across all complete roots.", ""])
    for root in result["roots"]:
        collisions = sum(pair["featureCollisions"]["equalFeatureSamples"] for pair in root["pairs"])
        lines.append(f"- `{root['rootId']}`: {len(root['candidateMeanPayoffs'])} candidates; "
                     f"descriptive mean-payoff range {root['descriptiveCandidateRange']['range']:.6g}; "
                     f"{collisions} paired feature collisions.")
    lines.extend(["", *[f"- {limit}" for limit in result["limitations"]]])
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--terminal-report", required=True)
    parser.add_argument("--bank-report", required=True)
    parser.add_argument("--verified-terminal-identity", required=True)
    parser.add_argument("--verified-bank-identity", required=True)
    parser.add_argument("--official-verification-completed", required=True, action="store_true")
    parser.add_argument("--json-output", required=True)
    parser.add_argument("--markdown-output", required=True)
    args = parser.parse_args(argv)
    try:
        repository = Path(__file__).resolve().parents[2]
        analysis_revision = committed_analysis_revision(repository)
        targets = [private_output(path, repository, os.environ) for path in (args.json_output, args.markdown_output)]
        require(targets[0] != targets[1], "JSON and Markdown destinations must differ")
        report, terminal_input = load_finalized_report(args.terminal_report, args.verified_terminal_identity)
        bank, bank_input = load_finalized_report(args.bank_report, args.verified_bank_identity)
        require(all(not target.is_relative_to(Path(source["path"]).parent) for target in targets
                    for source in (terminal_input, bank_input)), "Derivative must not modify finalized input run directories")
        result = analyze(report, bank, args.verified_terminal_identity, args.verified_bank_identity,
                         args.official_verification_completed)
        result["inputs"] = {"terminalReport": terminal_input, "bankReport": bank_input}
        result.update(review_binding(result, result["inputs"], analysis_revision, sha256(Path(__file__).read_bytes())))
        contents = [json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n", markdown(result)]
        for target, content in zip(targets, contents):
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("x", encoding="utf-8") as output:
                output.write(content)
        return 0
    except (DiagnosticRefusal, OSError, KeyError, TypeError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"REFUSED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
