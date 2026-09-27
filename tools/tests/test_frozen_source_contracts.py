"""Source guards for persisted seed domains and JVM entry point names.

These expectations are literal values from the pre-refactor public baseline.
They intentionally require review when a source move or seed-domain edit occurs.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class FrozenSourceContractsTest(unittest.TestCase):
    def test_policy_identity_wire_discriminators(self):
        source = (ROOT / "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyIdentity.kt").read_text(encoding="utf-8")
        self.assertIn('SEARCH_POLICY_BEHAVIOR_IDENTITY_PREFIX: String =\n    "search-teacher-behavior-v2-sha256"', source)
        self.assertIn("SEARCH_POLICY_BEHAVIOR_SCHEMA_V2: Int = 2", source)
        aliases = {
            "KnownDeckCardSpecification": "KnownDeckCardSpecification",
            "KnownDeckSpecification": "KnownDeckSpecification",
            "InputSchemaSpecification": "SearchTeacherInputSchemaSpecification",
            "ActionSpaceSpecification": "SearchTeacherActionSpaceSpecification",
            "EvaluatorSpecification": "SearchTeacherEvaluatorSpecification",
            "PolicyBehaviorSpecification": "SearchTeacherBehaviorSpecification",
        }
        for symbol, wire_name in aliases.items():
            with self.subTest(symbol=symbol):
                self.assertRegex(source, rf'@SerialName\("org\.mtgallium\.agent\.searchteacher\.{wire_name}"\)\s+data class {symbol}\b')
        policy_dir = ROOT / "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy"
        locations = [path for path in (policy_dir / "BeliefPreparation.kt", policy_dir / "BeliefTracker.kt") if path.is_file()]
        matches = sum(bool(re.search(
            r'@SerialName\("org\.mtgallium\.agent\.searchteacher\.SearchTeacherBeliefConfiguration"\)\s+data class BeliefConfig\b',
            path.read_text(encoding="utf-8"),
        )) for path in locations)
        self.assertEqual(1, matches, "BeliefConfig must retain its wire alias at exactly one known location")

    def test_seed_domains_at_their_call_sites(self):
        anchors = {
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/LivePolicy.kt": [
                r'ComponentSeeds\.derive\(gameId, decisionIndex, config\.baseSeed, "live-search"\)',
            ],
            "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefTracker.kt": [
                r'ComponentSeeds\.derive\(gameId, decisionIndex, "safe-information-conditioning"\)',
                r'ComponentSeeds\.derive\(gameId, decisionIndex, "live-belief-update"\)',
                r'ComponentSeeds\.derive\(seed, particleIndex, "privileged-particle"\)',
            ],
            "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt": [
                r'FUTURE_CHANCE_SEED_DOMAIN = "argentum-hypothetical-future-chance-v1"',
                r'ComponentSeeds\.derive\(seed, aliases\.getValue\(actor\), "argentum-heuristic"\)',
            ],
            "agent/infoset-planning/src/main/kotlin/org/mtgallium/agent/infoset/core/InformationSetSearch.kt": [
                r'ComponentSeeds\.derive\(searchSeed, simulationIndex, "root-particle"\)',
            ],
        }
        for relative, expressions in anchors.items():
            source = (ROOT / relative).read_text(encoding="utf-8")
            for expression in expressions:
                with self.subTest(file=relative, expression=expression):
                    self.assertRegex(source, expression)

    def test_model_input_byte_contract_markers(self):
        tensor = (ROOT / "agent/neural-policy/src/main/kotlin/org/mtgallium/agent/neural/FactualPolicyTensors.kt").read_text(encoding="utf-8")
        self.assertIn('val version: String = "factual-policy-json-bytes-v1"', tensor)
        self.assertIn("bytes.map { (it.toInt() and 255) + 1 }", tensor)
        kernel = (ROOT / "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/SemanticFeatures.kt").read_text(encoding="utf-8")
        self.assertIn("var hash = -3750763034362895579L", kernel)
        self.assertIn("hash *= 1099511628211L", kernel)
        self.assertIn('"candidate.payload"', kernel)

    def test_public_jvm_names_and_service_resource(self):
        names = {
            "org.mtgallium.agent.infoset.core.InformationSetSearch":
                "agent/infoset-planning/src/main/kotlin/org/mtgallium/agent/infoset/core/InformationSetSearch.kt",
            "org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld":
                "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt",
            "org.mtgallium.agent.infoset.core.ComponentSeeds":
                "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyCapabilities.kt",
            "org.mtgallium.research.workbench.NativePolicyProvider":
                "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/NativePolicies.kt",
        }
        for qualified, relative in names.items():
            source = (ROOT / relative).read_text(encoding="utf-8")
            package, _, symbol = qualified.rpartition(".")
            with self.subTest(name=qualified):
                self.assertRegex(source, rf"(?m)^package {re.escape(package)}$")
                self.assertRegex(source, rf"(?m)^(?:class|interface|object) {re.escape(symbol)}\b")
        service = ROOT / "research/workbench/src/test/resources/META-INF/services/org.mtgallium.research.workbench.NativePolicyProvider"
        self.assertEqual(b"org.mtgallium.research.workbench.TestNativePolicies\n", service.read_bytes())


if __name__ == "__main__":
    unittest.main()
