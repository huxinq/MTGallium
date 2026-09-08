/* Workbench completion adapter using the retained gameplay population authority. */
import java.nio.file.Path;
import java.util.*;
import kotlinx.serialization.json.*;
import org.mtgallium.evaluation.searchteacher.*;
import org.mtgallium.research.run.ResearchSourceProvenance;

class GameplayCompletionAudit {
    static JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof Boolean b) return JsonElementKt.JsonPrimitive(b);
        if (value instanceof Number n) return JsonElementKt.JsonPrimitive(n);
        if (value instanceof Map<?, ?> map) {
            var items = new LinkedHashMap<String, JsonElement>();
            map.forEach((key, item) -> items.put(key.toString(), json(item)));
            return new JsonObject(items);
        }
        if (value instanceof Collection<?> list) return new JsonArray(list.stream().map(GameplayCompletionAudit::json).toList());
        return JsonElementKt.JsonPrimitive(value.toString());
    }
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected RUN EXPECTED_IDENTITY");
        var retained = RetainedGameplayLengthsKt.loadRetainedGameplayLengths(Path.of(args[0]));
        if (!retained.getIdentity().equals(args[1])) throw new IllegalArgumentException("Gameplay identity differs");
        var comparisons = new ArrayList<Map<String, Object>>();
        for (var comparison : retained.getComparisons()) {
            long end = (long) comparison.getFirstPairIndex() + comparison.getInspectedPairs();
            var inspected = comparison.getRows().stream().filter(row -> row.getPairIndex() < end).toList();
            var overshoot = comparison.getRows().stream().filter(row -> row.getPairIndex() >= end).toList();
            var outcomes = new TreeMap<String, Integer>();
            for (var row : inspected) if (row.getEligible()) outcomes.merge(row.getCandidateOutcome(), 1, Integer::sum);
            var item = new LinkedHashMap<String, Object>();
            item.put("control", SearchTeacherSupportKt.getEvidenceJson().encodeToJsonElement(
                SearchTeacherCalibrationPolicy.Companion.serializer(), comparison.getControl()));
            item.put("candidate", SearchTeacherSupportKt.getEvidenceJson().encodeToJsonElement(
                SearchTeacherCalibrationPolicy.Companion.serializer(), comparison.getCandidate()));
            item.put("executedPairs", comparison.getRows().stream().map(GameplayLengthObservation::getPairIndex).distinct().count());
            item.put("executedGames", comparison.getRows().size());
            item.put("inspectedPairs", comparison.getInspectedPairs());
            item.put("inspectedGames", inspected.size());
            item.put("eligibleInspectedGames", inspected.stream().filter(GameplayLengthObservation::getEligible).count());
            item.put("ineligibleInspectedGames", inspected.stream().filter(row -> !row.getEligible()).count());
            item.put("overshootPairs", overshoot.stream().map(GameplayLengthObservation::getPairIndex).distinct().count());
            item.put("overshootGames", overshoot.size());
            item.put("eligibleInspectedOutcomes", outcomes);
            comparisons.add(item);
        }
        var origins = new ArrayList<Map<String, Object>>();
        for (var origin : retained.getOrigins()) origins.add(Map.of("researchRunIdentity", origin.getIdentity(),
            "source", SearchTeacherSupportKt.getEvidenceJson().encodeToJsonElement(
                ResearchSourceProvenance.Companion.serializer(), origin.getSource()), "workers", origin.getWorkers()));
        System.out.println(json(Map.of("researchRunIdentity", retained.getIdentity(), "disposition", retained.getDisposition(),
            "origins", origins, "comparisons", comparisons, "deckHash", retained.getDeckHash(), "cardPoolHash", retained.getCardPoolHash(),
            "stages", Map.of("gameplayPopulation", "VERIFIED"),
            "recovery", Map.of("automaticResume", false, "eligibility", "An explicit continuation plan must satisfy the native stopping-rule contract."),
            "limits", List.of("Only eligible games in the inspected prefix contribute to the outcome counts.",
                "Overshoot and ineligible games are separate populations, not extra inspected outcomes.",
                "The recorded stopping disposition retains its original meaning; this audit makes no new strength claim."))));
    }
}
