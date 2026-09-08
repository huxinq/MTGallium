package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherCli
import org.mtgallium.evaluation.searchteacher.cli.SearchTeacherSuites

/** Stable JVM entry point shared by direct CLI calls and frozen research runtimes. */
fun main(args: Array<String>) {
    runSearchTeacher(Path.of("").toAbsolutePath().normalize(), args)
}

internal fun runSearchTeacher(root: Path, args: Array<String>) {
    val options = SearchTeacherCli.parse(args)
    SearchTeacherSuites.require(options.suite).run(root, options)
}
