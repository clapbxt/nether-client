# Builds the in-game client: agent\build\nether-agent.jar, nether-agent-boot.jar and schema.json.
# The build itself is Gradle (agent\build.gradle.kts, versions pinned in agent\gradle\libs.versions.toml); this script
# stays so existing commands and tools keep working. Needs a JDK 17+ on PATH or in JAVA_HOME. Gradle only rewrites
# the jars when an input changed, so the launcher build doesn't recompile nether-core for nothing.
# -Out <dir>: also copy the three outputs there.
param([string]$Out = "")
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
& (Join-Path $root "gradlew.bat") -p $root assemble --console=plain
if ($LASTEXITCODE -ne 0) { throw "agent build failed" }
$build = Join-Path $root "build"
if ($Out) {
    $dst = [IO.Path]::GetFullPath($Out)
    New-Item -ItemType Directory -Force $dst | Out-Null
    foreach ($f in "nether-agent.jar", "nether-agent-boot.jar", "schema.json") { Copy-Item (Join-Path $build $f) $dst -Force }
    $build = $dst
}
Write-Host "built" (Join-Path $build "nether-agent.jar")
