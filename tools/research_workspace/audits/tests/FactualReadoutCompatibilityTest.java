/* Explicit compatibility regression: accepts a retained allocation, creates only
 * synthetic unexecuted rows in memory, and never launches research or writes it. */
import java.nio.file.Path;
import java.util.*;
import org.mtgallium.evaluation.searchteacher.*;

class FactualReadoutCompatibilityTest {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected retained allocation report path");
        var allocation = SearchTeacherSupportKt.readEvidenceJson(Path.of(args[0]), FactualResidualAllocation.Companion.serializer());
        var rows = new ArrayList<FactualResidualSearchRow>();
        for (var game : allocation.getGames()) if (game.getRole() == FactualResidualDataRole.SCREEN)
            for (int repetition = 0; repetition < 2; repetition++) for (var arm : FactualResidualArm.values())
                rows.add(new FactualResidualSearchRow(game.getRoot().getAssignment().getRootId(), game.getSeedGroupId(),
                    game.getGameId(), game.getViewer(), game.getLeg(), repetition, arm, 7L,
                    FactualResidualReadoutDisposition.UNEXECUTED, false, 0.0, null, null, List.of(), null,
                    null, List.of(), null, 0, "Synthetic unexecuted compatibility witness"));
        // Valid unexecuted populations are not promoted to completed searches.
        FactualResidualCompletionAudit.validateReadout(allocation, rows, null);
        var gate = FactualResidualRootReadoutKt.factualResidualReadoutGate(allocation, rows);
        FactualResidualCompletionAudit.validateReadout(allocation, rows, gate);
        FactualResidualCompletionAudit.validateReadout(allocation, List.of(), null);
        var duplicate = new ArrayList<>(rows);
        duplicate.set(1, duplicate.get(0));
        expectRefusal(() -> FactualResidualCompletionAudit.validateReadout(allocation, duplicate, null));
        expectRefusal(() -> FactualResidualCompletionAudit.validateReadout(allocation, rows.subList(1, rows.size()), null));
        expectRefusal(() -> FactualResidualCompletionAudit.validateReadout(allocation, List.of(), gate));
        System.out.println("PASS: valid unexecuted population; null-gate duplicate/missing rows and mismatched gate refuse");
    }
    static void expectRefusal(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid readout population was accepted");
    }
}
