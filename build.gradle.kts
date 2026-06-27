val gitBranch = git("branch", "--show-current").getOrElse("unknown")
val gitCommitHash = git("rev-parse", "--short", "HEAD").getOrElse("unknown")

extra["gitBranch"] = gitBranch
extra["gitCommitHash"] = gitCommitHash

fun git(vararg args: String) = providers.exec {
    commandLine("git", *args)
}.standardOutput.asText.map(String::trim)