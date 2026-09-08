package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import org.mtgallium.research.run.ResearchRunArtifacts

internal fun finalizeResearchWorkflowArtifacts(directory: Path, identity: String) {
    ResearchRunArtifacts(directory, identity).also { artifacts ->
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                artifacts.register(directory.relativize(path).toString())
            }
        }
        artifacts.finalize()
    }
    ResearchRunArtifacts.loadAndVerify(directory, identity)
}
