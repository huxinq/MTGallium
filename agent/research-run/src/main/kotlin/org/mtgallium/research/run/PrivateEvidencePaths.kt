package org.mtgallium.research.run

import java.nio.file.Path

/**
 * Resolves mutable, privileged research output away from a source checkout when
 * the checkout is used as public source.  The historical in-checkout layout is
 * retained only for private checkouts that have not opted into this authority.
 */
object PrivateEvidencePaths {
    const val ROOT_ENVIRONMENT_VARIABLE = "MTGALLIUM_PRIVATE_EVIDENCE_ROOT"
    const val PUBLIC_SOURCE_ENVIRONMENT_VARIABLE = "MTGALLIUM_PUBLIC_SOURCE"

    fun resolve(repositoryRoot: Path, historicalRelative: String): Path {
        val repository = repositoryRoot.toAbsolutePath().normalize()
        val configured = System.getenv(ROOT_ENVIRONMENT_VARIABLE)?.trim().orEmpty()
        if (configured.isEmpty()) {
            require(System.getenv(PUBLIC_SOURCE_ENVIRONMENT_VARIABLE) != "1") {
                "$ROOT_ENVIRONMENT_VARIABLE is required when $PUBLIC_SOURCE_ENVIRONMENT_VARIABLE=1; " +
                    "privileged evidence must not be written into a public source checkout"
            }
            return ResearchRunFiles.resolveBelow(repository, historicalRelative)
        }

        val externalRoot = Path.of(configured).toAbsolutePath().normalize()
        require(externalRoot != repository && !externalRoot.startsWith(repository)) {
            "$ROOT_ENVIRONMENT_VARIABLE must be outside the source checkout: $externalRoot"
        }
        val portable = historicalRelative.removePrefix("reports/")
        return ResearchRunFiles.resolveBelow(externalRoot, portable)
    }
}
