"""Synthetic retrieval witnesses; no retained private evidence or Gradle needed."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.research_workspace import evidence


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.run = self.root / "run"
        self.run.mkdir()
        self.producer = {
            "schemaVersion": 1,
            "researchRunIdentity": "original-run",
            "sourceProvenance": {"outer": {"revision": "original-mtgallium"},
                                 "argentum": {"revision": "original-argentum"}},
            "rows": [None, {"state": "STOPPED", "outcome": None},
                     {"state": "REFUSED", "error": {"kind": "unsupported", "value": None}},
                     {"state": "EXCLUDED", "observed": False}, [None, "retained"]],
            "a/b": {"~key": [1, None, "literal"]},
            "": "empty-key",
        }
        self.payload = (json.dumps(self.producer) + "\n").encode()
        self.manifest = self.make_manifest({"report.json": self.payload,
                                            "canonical/replay.json": b'{"secret":"PRIVATE_REPLAY_CONTENT"}'})
        self.calls = []
        self.extractor = {"sourceRevision": "new-extractor-source", "dirty": True,
                          "scriptSha256": "b" * 64, "trackedDiffSha256": "c" * 64}

    def make_manifest(self, artifacts):
        entries = []
        for relative, data in artifacts.items():
            path = self.run / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            entries.append({"relativePath": relative, "sha256": sha256(data), "bytes": len(data)})
        manifest = {"schemaVersion": 1, "researchRunIdentity": "original-run", "state": "COMPLETE",
                    "artifacts": entries}
        self.write_manifest(manifest)
        return manifest

    def write_manifest(self, manifest):
        data = (json.dumps(manifest) + "\n").encode()
        (self.run / evidence.MANIFEST_NAME).write_bytes(data)
        return data

    def verifier(self, directory):
        """Test double for the source verifier, authenticating the complete fixture."""
        self.calls.append(directory)
        data = (directory / evidence.MANIFEST_NAME).read_bytes()
        manifest = json.loads(data)
        for entry in manifest["artifacts"]:
            content = (directory / entry["relativePath"]).read_bytes()
            if len(content) != entry["bytes"] or sha256(content) != entry["sha256"]:
                raise evidence.EvidenceError("Source verifier refused corrupted artifact")
        return {"manifest": manifest, "manifestSha256": sha256(data)}

    def transport_verifier(self, directory):
        """Transport-only double to isolate Python's defensive post-verification checks."""
        data = (directory / evidence.MANIFEST_NAME).read_bytes()
        return {"manifest": json.loads(data), "manifestSha256": sha256(data)}

    def extract(self, pointer="/rows", artifact="report.json", destination=None, verifier=None):
        return evidence.extract_json(self.run, artifact, pointer, destination or self.root / "export",
                                     verifier or self.verifier, self.extractor)

    def test_inspection_returns_inventory_and_identity_without_values(self):
        result = evidence.inspect_run(self.run, self.verifier)
        self.assertEqual([self.run], self.calls)
        self.assertEqual("bytes-verified", result["status"])
        self.assertEqual("original-run", result["declared"]["researchRunIdentity"])
        self.assertEqual("COMPLETE", result["declared"]["state"])
        self.assertEqual(self.manifest["artifacts"], result["artifacts"])
        self.assertNotIn("PRIVATE_REPLAY_CONTENT", json.dumps(result))
        self.assertNotIn("new-extractor-source", json.dumps(result))
        self.assertNotIn("sourceProvenance", result["declared"])
        self.assertIn("not scientific validity", " ".join(result["limitations"]))

    def test_source_schema_default_does_not_invent_an_observed_field(self):
        del self.manifest["state"]
        del self.manifest["schemaVersion"]
        self.write_manifest(self.manifest)
        result = evidence.inspect_run(self.run, self.verifier)
        self.assertEqual("bytes-verified", result["status"])
        self.assertNotIn("state", result["declared"])
        self.assertNotIn("schemaVersion", result["declared"])

    def test_source_verifier_refusal_propagates_before_export(self):
        (self.run / "canonical/replay.json").write_bytes(b"corrupted unrelated registered artifact")
        with self.assertRaisesRegex(evidence.EvidenceError, "Source verifier refused"):
            self.extract()
        self.assertFalse((self.root / "export").exists())

    def test_extraction_preserves_whole_rows_nulls_errors_and_source_identities(self):
        receipt = self.extract()
        output = self.root / "export" / "value.json"
        self.assertEqual(self.producer["rows"], json.loads(output.read_bytes()))
        self.assertEqual(receipt, json.loads((output.parent / "receipt.json").read_bytes()))
        self.assertEqual("original-run", receipt["input"]["researchRunIdentity"])
        declaration = receipt["input"]["declaredProducer"]
        self.assertEqual("original-run", declaration["researchRunIdentity"])
        self.assertEqual(1, declaration["artifactDeclarations"]["schemaVersion"])
        self.assertEqual(self.producer["sourceProvenance"], declaration["artifactDeclarations"]["sourceProvenance"])
        self.assertEqual(self.extractor, receipt["extractor"])
        self.assertEqual(sha256(output.read_bytes()), receipt["output"]["sha256"])
        self.assertEqual(sha256(self.payload), receipt["input"]["artifact"]["sha256"])
        self.assertEqual(sha256((self.run / evidence.MANIFEST_NAME).read_bytes()), receipt["input"]["manifestSha256"])
        self.assertEqual("json-pointer-retrieval", receipt["operation"])

    def test_rfc6901_root_escapes_empty_key_and_null(self):
        for index, (pointer, expected) in enumerate([
            ("", self.producer), ("/a~1b/~0key/1", None), ("/", "empty-key"),
        ]):
            with self.subTest(pointer=pointer):
                destination = self.root / f"export-{index}"
                self.extract(pointer, destination=destination)
                self.assertEqual(expected, json.loads((destination / "value.json").read_bytes()))

    def test_json_pointer_refusals_never_create_output(self):
        for pointer in ("#/rows", "rows", "/missing", "/rows/01", "/rows/-", "/rows/99",
                        "/rows/+1", "/rows/0/child", "/a~2b", "/a~", "/rows/" + "9" * 5000):
            with self.subTest(pointer=pointer[:30]):
                with self.assertRaises(evidence.EvidenceError):
                    self.extract(pointer)
                self.assertFalse((self.root / "export").exists())

    def test_number_tokens_and_array_order_are_preserved_exactly(self):
        payload = b'{"numbers":[1.2345678901234567890123456789,1e400,-0,123456789012345678901234567890]}'
        self.make_manifest({"precise.json": payload})
        self.extract("/numbers", artifact="precise.json")
        self.assertEqual(payload[len(b'{"numbers":'):-1] + b"\n",
                         (self.root / "export" / "value.json").read_bytes())

    def test_fresh_exports_have_deterministic_value_bytes(self):
        first = self.extract(destination=self.root / "first")
        second = self.extract(destination=self.root / "second")
        self.assertEqual((self.root / "first/value.json").read_bytes(),
                         (self.root / "second/value.json").read_bytes())
        self.assertEqual(first["output"]["sha256"], second["output"]["sha256"])
        self.assertEqual(first["input"], second["input"])

    def test_selected_byte_mutation_after_verification_is_refused(self):
        def mutate(directory):
            result = self.verifier(directory)
            (directory / "report.json").write_bytes(b"X" * len(self.payload))
            return result

        with self.assertRaisesRegex(evidence.EvidenceError, "Selected artifact bytes changed"):
            self.extract(verifier=mutate)
        self.assertFalse((self.root / "export").exists())

    def test_manifest_mutation_after_verification_is_refused(self):
        def mutate(directory):
            result = self.verifier(directory)
            self.manifest["researchRunIdentity"] = "substituted"
            self.write_manifest(self.manifest)
            return result

        with self.assertRaisesRegex(evidence.EvidenceError, "Manifest changed"):
            self.extract(verifier=mutate)

    def test_source_verifier_transport_manifest_mismatch_is_refused(self):
        def mismatch(directory):
            result = self.verifier(directory)
            result["manifest"]["researchRunIdentity"] = "invented"
            return result

        with self.assertRaisesRegex(evidence.EvidenceError, "Manifest changed"):
            evidence.inspect_run(self.run, mismatch)

    def test_unregistered_artifact_is_refused(self):
        (self.run / "unregistered.json").write_text("{}")
        with self.assertRaisesRegex(evidence.EvidenceError, "not registered"):
            self.extract(artifact="unregistered.json")

    def test_traversal_and_absolute_paths_are_refused(self):
        for artifact in ("../outside.json", "/tmp/outside.json", "nested/../../outside.json",
                         "./report.json", "nested//report.json", "nested\\report.json", "C:/outside.json"):
            with self.subTest(artifact=artifact):
                with self.assertRaises(evidence.EvidenceError):
                    self.extract(artifact=artifact)

    def test_malicious_registered_path_is_refused(self):
        self.manifest["artifacts"][0]["relativePath"] = "../outside.json"
        self.write_manifest(self.manifest)
        with self.assertRaisesRegex(evidence.EvidenceError, "traversal"):
            self.extract(artifact="../outside.json", verifier=self.transport_verifier)

    def test_selected_symlink_and_linked_parent_are_refused(self):
        outside = self.root / "outside.json"
        outside.write_bytes(self.payload)
        (self.run / "report.json").unlink()
        (self.run / "report.json").symlink_to(outside)
        with self.assertRaisesRegex(evidence.EvidenceError, "without symlinks"):
            self.extract(verifier=self.transport_verifier)
        (self.run / "report.json").unlink()
        (self.run / "report.json").write_bytes(self.payload)
        (self.run / "nested").symlink_to(self.root, target_is_directory=True)
        self.manifest["artifacts"][0]["relativePath"] = "nested/outside.json"
        self.write_manifest(self.manifest)
        with self.assertRaisesRegex(evidence.EvidenceError, "without symlinks"):
            self.extract(artifact="nested/outside.json", verifier=self.transport_verifier)

    def test_root_symlink_is_refused_before_calling_verifier(self):
        alias = self.root / "alias"
        alias.symlink_to(self.run, target_is_directory=True)
        with self.assertRaisesRegex(evidence.EvidenceError, "without symlinks"):
            evidence.inspect_run(alias, self.verifier)
        self.assertEqual([], self.calls)

    def test_oversized_json_refuses_with_streaming_direction(self):
        with patch.object(evidence, "MAX_JSON_BYTES", len(self.payload) - 1):
            with self.assertRaisesRegex(evidence.EvidenceError, "specialized streaming tool"):
                self.extract()
        self.assertFalse((self.root / "export").exists())

    def test_invalid_json_duplicate_keys_and_nonfinite_tokens_are_refused(self):
        for content in (b'{"unfinished":', b'{"a":1,"a":2}', b'{"n":NaN}', b'{"n":Infinity}', b'"\xff"'):
            with self.subTest(content=content):
                self.make_manifest({"invalid.json": content})
                with self.assertRaises(evidence.EvidenceError):
                    self.extract("", artifact="invalid.json")
                self.assertFalse((self.root / "export").exists())

    def test_describe_shows_only_top_level_keys_and_type(self):
        preview = evidence.describe_artifact(self.run, "canonical/replay.json", self.verifier)
        self.assertEqual("object", preview["jsonType"])
        self.assertEqual(["secret"], preview["topLevelKeys"])
        self.assertNotIn("PRIVATE_REPLAY_CONTENT", json.dumps(preview))
        self.assertNotIn("original-mtgallium", json.dumps(preview))

    def test_existing_export_directory_file_and_symlink_are_unchanged(self):
        for kind in ("directory", "file", "symlink"):
            with self.subTest(kind=kind):
                destination = self.root / f"existing-{kind}"
                if kind == "directory":
                    destination.mkdir()
                elif kind == "file":
                    destination.write_text("original")
                else:
                    destination.symlink_to(self.root / "missing")
                with self.assertRaisesRegex(evidence.EvidenceError, "fresh destination"):
                    self.extract(destination=destination)
                if kind == "directory":
                    self.assertEqual([], list(destination.iterdir()))
                elif kind == "file":
                    self.assertEqual("original", destination.read_text())
                else:
                    self.assertTrue(destination.is_symlink())
                    self.assertFalse((self.root / "missing").exists())

    def test_output_parent_symlink_and_traversal_are_refused(self):
        outside = self.root / "outside"
        outside.mkdir()
        alias = self.root / "output-alias"
        alias.symlink_to(outside, target_is_directory=True)
        for destination in (alias / "export", outside / ".." / "escape"):
            with self.subTest(destination=destination):
                with self.assertRaises(evidence.EvidenceError):
                    self.extract(destination=destination)
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse((self.root / "escape").exists())

    def test_extractor_binding_is_required_and_not_inferred_from_producer(self):
        for missing in ("sourceRevision", "dirty", "scriptSha256"):
            with self.subTest(missing=missing):
                extractor = dict(self.extractor)
                del extractor[missing]
                with self.assertRaises(evidence.EvidenceError):
                    evidence.extract_json(self.run, "report.json", "", self.root / "export",
                                          self.verifier, extractor)
        self.assertEqual([], self.calls)


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def marker(self, directory, name, document):
        path = self.root / directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document))
        return path

    def test_both_markers_sorted_bounded_unverified_without_report_loading(self):
        self.marker("z-last", "experiment.json", {"experimentIdentity": "z"})
        self.marker("a-first", evidence.MANIFEST_NAME, {"researchRunIdentity": "old-run", "state": "COMPLETE"})
        self.marker("a-first", "experiment.json", {"protocol": "frozen-protocol", "sourceRevision": "old-source"})
        (self.root / "a-first" / "report.json").write_text("THIS IS NOT JSON AND MUST NOT BE OPENED")
        result = evidence.find_runs(self.root, limit=1)
        self.assertEqual(1, len(result["entries"]))
        entry = result["entries"][0]
        self.assertEqual(str(self.root / "a-first"), entry["directory"])
        self.assertEqual("unverified", entry["status"])
        self.assertEqual([evidence.MANIFEST_NAME, "experiment.json"],
                         [item["name"] for item in entry["documents"]])
        self.assertEqual("old-source", entry["documents"][1]["declared"]["sourceRevision"])
        self.assertTrue(result["truncated"])
        self.assertEqual([], result["errors"])
        self.assertEqual(result, evidence.find_runs(self.root, limit=1))

    def test_query_uses_observed_metadata_and_missing_source_stays_absent(self):
        self.marker("one", "experiment.json", {"bindings": {"protocol": "TARGET-protocol"}})
        self.marker("two", evidence.MANIFEST_NAME, {"researchRunIdentity": "other"})
        result = evidence.find_runs(self.root, query="target-protocol")
        self.assertEqual(1, len(result["entries"]))
        declared = result["entries"][0]["documents"][0]["declared"]
        self.assertEqual({"bindings": {"protocol": "TARGET-protocol"}}, declared)

    def test_question_and_frozen_request_source_are_searchable_as_declarations(self):
        self.marker("attempt", "experiment.json", {
            "name": "casting-study", "kind": "sequential",
            "design": {"question": "Does the casting kernel reduce measured decision time?",
                       "intervention": "frozen casting kernel", "population": "declared development seeds"},
            "lineage": {"draftSha256": "d" * 64, "reason": "changed continuation"},
        })
        request = {
            "source": {"sourceRevision": "original-source-sha", "argentumRevision": "original-engine-sha"},
            "build": {"identity": "frozen-build", "manifestSha256": "e" * 64},
            "command": ["never-executed-or-returned"],
        }
        self.marker("attempt", "request.json", request)
        for query in ("casting kernel reduce", "original-source-sha"):
            with self.subTest(query=query):
                result = evidence.find_runs(self.root, query=query)
                self.assertEqual(1, len(result["entries"]))
                entry = result["entries"][0]
                self.assertEqual("unverified", entry["status"])
                declaration = entry["documents"][1]["declared"]
                self.assertEqual(request["source"], declaration["source"])
                self.assertEqual(request["build"], declaration["build"])
                self.assertNotIn("sourceRevision", declaration)
                self.assertNotIn("command", declaration)
                self.assertIn("question", entry["documents"][0]["declared"]["design"])

    def test_malformed_duplicate_and_non_object_markers_report_errors(self):
        for index, text in enumerate(("{bad", '{"protocol":1,"protocol":2}', "[]")):
            path = self.marker(str(index), "experiment.json", {})
            path.write_text(text)
        result = evidence.find_runs(self.root)
        self.assertEqual(3, len(result["entries"]))
        self.assertEqual(3, len(result["errors"]))
        self.assertTrue(all(not item["documents"] for item in result["entries"]))
        self.assertFalse(result["errorsTruncated"])

    def test_walk_does_not_follow_directory_links_and_marker_links_refuse(self):
        target = self.marker("target", evidence.MANIFEST_NAME, {"researchRunIdentity": "run"})
        (self.root / "alias").symlink_to(target.parent, target_is_directory=True)
        linked = self.root / "linked-file"
        linked.mkdir()
        (linked / "experiment.json").symlink_to(target)
        result = evidence.find_runs(self.root)
        self.assertEqual([str(linked), str(target.parent)], [item["directory"] for item in result["entries"]])
        self.assertEqual(1, len(result["errors"]))
        self.assertIn("without symlinks", result["errors"][0]["error"])

    def test_discovery_read_bound_and_error_bound_are_explicit(self):
        self.marker("one", "experiment.json", {"unused": "x" * 100})
        self.marker("two", "experiment.json", {"unused": "x" * 100})
        with patch.object(evidence, "MAX_DISCOVERY_BYTES", 20):
            result = evidence.find_runs(self.root, query="does-not-match", limit=1)
        self.assertEqual([], result["entries"])
        self.assertEqual(1, len(result["errors"]))
        self.assertTrue(result["errorsTruncated"])
        self.assertIn("streaming", result["errors"][0]["error"])


if __name__ == "__main__":
    unittest.main()
