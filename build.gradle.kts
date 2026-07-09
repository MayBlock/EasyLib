val gitBranch = git("branch", "--show-current").getOrElse("unknown")
val gitCommitHash = git("rev-parse", "--short", "HEAD").getOrElse("unknown")

extra["gitBranch"] = gitBranch
extra["gitCommitHash"] = gitCommitHash

// git 元数据尽力而为：命令失败（如无 .git 的 tarball 构建）或输出为空（如 JitPack 的 detached HEAD）
// 时 provider 置空，由调用侧回退 "unknown"。
fun git(vararg args: String) = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { null } }
