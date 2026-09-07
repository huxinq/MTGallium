import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import position_bank_terminal_diagnostics as diagnostics


def fixture():
    candidates = [{"signature": "a"}, {"signature": "b"}, {"signature": "c"}]
    samples = []
    for i in range(2):
        for candidate in candidates:
            samples.append({"candidateSignature": candidate["signature"],
                            "coordinate": {"replicate": i, "particleIndex": i, "particleSamplingSeed": 11,
                                           "futureSeed": 20 + i, "continuationSeed": 30 + i},
                            "payoff": 1 if candidate["signature"] == "a" else -1,
                            "policyDecisions": 0, "rootPolicyDecisions": {"decisions": 0},
                            "opponentPolicyDecisions": {"decisions": 0},
                            "hypotheticalAfterActionFeatures": {"life": 1.0},
                            "hypotheticalAfterActionInformationDigest": candidate["signature"],
                            "terminalImmediatelyAfterAction": False})
    summary = dict(zip(diagnostics.COUNTS, (6, 6, 6, 0, 0)), complete=True)
    root = {"rootId": "root", "actor": "p0", "beliefBaseSeed": 7, "candidates": candidates,
            "summary": summary, "samples": samples}
    source = {"expectedArgentumRevision": "engine", "outer": {"revision": "treatment"},
              "argentum": {"revision": "engine"}}
    report = {"schemaVersion": 1, "researchRunIdentity": "terminal", "sourceProvenance": source,
              "plan": {"expectedBankIdentity": "bank", "partition": "DEVELOPMENT", "samplesPerRoot": 2,
                       "rootIds": ["root"]}, "roots": [root], "completeRoots": 1, "invalidRoots": 0, "complete": True,
              **dict(zip(diagnostics.COUNTS, (6, 6, 6, 0, 0)))}
    bank = {"schemaVersion": 1, "bankIdentity": "bank", "sourceProvenance": source, "complete": True,
            "roots": [{"rootId": "root", "partition": "DEVELOPMENT", "actor": "p0", "baseSeed": 7,
                       "decisionFamily": "PRIORITY", "reconstructedCandidates": copy.deepcopy(candidates)}]}
    return report, bank


def analyze(report, bank):
    return diagnostics.analyze(report, bank, "terminal", "bank", True)


class TerminalDiagnosticsTest(unittest.TestCase):
    def test_review_identity_binds_analysis_revision_and_exact_input_hashes(self):
        report, bank = fixture()
        result = analyze(report, bank)
        inputs = {"terminalReport": {"sha256": "terminal-report", "manifestSha256": "terminal-manifest"},
                  "bankReport": {"sha256": "bank-report", "manifestSha256": "bank-manifest"}}
        identity = diagnostics.review_binding(result, inputs, "analysis-revision-a", "script-hash")
        self.assertEqual(identity, diagnostics.review_binding(result, inputs, "analysis-revision-a", "script-hash"))
        self.assertNotEqual(identity["reviewDerivativeIdentity"],
                            diagnostics.review_binding(result, inputs, "analysis-revision-b", "script-hash")["reviewDerivativeIdentity"])
        inputs["terminalReport"]["sha256"] = "changed-report"
        self.assertNotEqual(identity["reviewDerivativeIdentity"],
                            diagnostics.review_binding(result, inputs, "analysis-revision-a", "script-hash")["reviewDerivativeIdentity"])

    def test_exact_sign_and_holm_preserve_ties_and_original_order(self):
        self.assertEqual(1.0, diagnostics.sign_test(0, 0))
        self.assertEqual(1.0, diagnostics.sign_test(4, 4))
        self.assertEqual(0.0078125, diagnostics.sign_test(8, 0))
        self.assertEqual([0.09, 0.04, 0.09, 1.0], diagnostics.holm_adjust([0.03, 0.01, 0.04, 1.0]))
        self.assertEqual([1.0, 1.0], diagnostics.holm_adjust([1.0, 1.0]))

    def test_full_pair_population_collisions_and_report_wide_binary_rule(self):
        report, bank = fixture()
        result = analyze(report, bank)
        root = result["roots"][0]
        self.assertEqual(3, result["signTestFamily"]["holmTests"])
        self.assertEqual([2.0, 2.0, 0.0], [p["meanFirstMinusSecond"] for p in root["pairs"]])
        self.assertEqual([2, 2, 0], [p["discordant"] for p in root["pairs"]])
        self.assertEqual(1.0, root["pairs"][2]["signTest"]["twoSidedP"])
        self.assertEqual({"groups": 2, "samplesInvolved": 6, "groupsWithDifferentInformationDigests": 2,
                          "immediateTerminalSamples": 0}, root["featureCollisionGroups"])
        self.assertEqual(6, sum(p["featureCollisions"]["equalFeatureSamples"] for p in root["pairs"]))
        report["roots"][0]["samples"][0]["payoff"] = 0
        result = analyze(report, bank)
        self.assertEqual(0, result["signTestFamily"]["holmTests"])
        self.assertTrue(all(not p["signTest"]["available"] for p in result["roots"][0]["pairs"]))

    def test_missing_duplicate_and_mismatched_pair_coordinates_fail_closed(self):
        for mutation in (lambda samples: samples.pop(), lambda samples: samples.append(copy.deepcopy(samples[0])),
                         lambda samples: samples[1]["coordinate"].update(futureSeed=999)):
            report, bank = fixture()
            mutation(report["roots"][0]["samples"])
            with self.assertRaises(diagnostics.DiagnosticRefusal):
                analyze(report, bank)

    def test_typed_refusal_preserves_invalid_root_and_never_produces_values(self):
        report, bank = fixture()
        sample = report["roots"][0]["samples"][0]
        sample.update(payoff=None, refusal="CONTINUATION_LIMIT", policyDecisions=None,
                      rootPolicyDecisions=None, opponentPolicyDecisions=None)
        report["roots"][0]["summary"].update(terminalSamples=5, refusedSamples=1, complete=False)
        report.update(terminalSamples=5, refusedSamples=1, complete=False, completeRoots=0, invalidRoots=1)
        result = analyze(report, bank)
        self.assertEqual([], result["roots"])
        self.assertEqual(1, result["populations"]["invalidRoots"])
        self.assertEqual("CONTINUATION_LIMIT", result["invalidRoots"][0]["sampleRefusals"][0]["refusal"])
        self.assertNotIn("candidateMeanPayoffs", result["invalidRoots"][0])
        sample["refusal"] = "UNKNOWN_FAILURE"
        with self.assertRaises(diagnostics.DiagnosticRefusal):
            analyze(report, bank)

    def test_verification_attestation_final_hash_and_private_destination(self):
        report, bank = fixture()
        with self.assertRaises(diagnostics.DiagnosticRefusal):
            diagnostics.analyze(report, bank, "terminal", "bank")
        with tempfile.TemporaryDirectory(dir="/tmp") as directory:
            base = Path(directory)
            path = base / "report.json"
            data = json.dumps(report).encode()
            path.write_bytes(data)
            manifest = {"researchRunIdentity": "terminal", "state": "COMPLETE", "artifacts": [
                {"relativePath": "report.json", "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}]}
            (base / "research-run-manifest.json").write_text(json.dumps(manifest))
            loaded, identity = diagnostics.load_finalized_report(path, "terminal")
            self.assertEqual(report, loaded)
            self.assertEqual(hashlib.sha256(data).hexdigest(), identity["sha256"])
            del manifest["state"]
            (base / "research-run-manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(diagnostics.DiagnosticRefusal):
                diagnostics.load_finalized_report(path, "terminal")
            manifest["state"] = "COMPLETE"
            (base / "research-run-manifest.json").write_text(json.dumps(manifest))
            path.write_text("{}")
            with self.assertRaises(diagnostics.DiagnosticRefusal):
                diagnostics.load_finalized_report(path, "terminal")
            repository = base / "repo"
            repository.mkdir()
            (repository / ".git").mkdir()
            env = {"MTGALLIUM_PUBLIC_SOURCE": "1", "MTGALLIUM_PRIVATE_EVIDENCE_ROOT": str(base / "private")}
            target = base / "private/search-teacher/work/review/summary.json"
            # Isolate the synthetic checkout from unrelated host ancestor Git metadata.
            with patch.object(Path, "exists", lambda path: path == repository / ".git"):
                self.assertEqual(target, diagnostics.private_output(str(target), repository, env))
                with self.assertRaises(diagnostics.DiagnosticRefusal):
                    diagnostics.private_output(str(repository / "summary.json"), repository, env)
            with self.assertRaises(diagnostics.DiagnosticRefusal):
                diagnostics.private_output(str(target), repository, {"MTGALLIUM_PUBLIC_SOURCE": "1"})


if __name__ == "__main__":
    unittest.main()
