# Builds agent\build\nether-agent.jar (needs JDK 9+ to run javac; compiles against the LWJGL/ASM jars the launcher downloaded).
# Output is Java 8 bytecode (--release 8): one jar for 1.8.9 (Java 8, LWJGL2), 1.13+ (LWJGL3 GLFW) and 26.3+ (SDL3). Three javac passes: LWJGL3 classes, the LWJGL2-only ones (PlatLwjgl2, GlCompat), PlatSdl.
# ASM is bundled RELOCATED to dev.nether.shaded.asm so it never clashes with Fabric's own ASM.
# Inputs are picked by version, never by file-name order: the newest ASM (all four artifacts the same version) and the
# OLDEST LWJGL 3 that has core, GLFW and OpenGL (compiling against the oldest API keeps every game version linkable). The build happens in build.new and replaces build only when it succeeded.
param([string]$Out = "")
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$lib = Join-Path $env:APPDATA "NetherClient\libraries"

# Maven-style version string -> sortable [version] (2.9.4-nightly-20150209 -> 2.9.4)
function Ver($s) { $m = [regex]::Match($s, "^\d+(\.\d+){0,3}"); if ($m.Success) { $v = $m.Value; if ($v -notmatch "\.") { $v += ".0" }; [version]$v } else { [version]"0.0" } }
# every <artifact>-<version>.jar under the library folder as @{ Version; Path }
function Jars($artifact) {
    Get-ChildItem @($lib, $env:NETHER_LIB_EXTRA | Where-Object { $_ -and (Test-Path $_) }) -Recurse -Filter "$artifact-*.jar" | Where-Object { $_.Name -notmatch "natives|sources|javadoc" } | ForEach-Object {
        $v = $_.BaseName.Substring($artifact.Length + 1)
        if ($v -match "^\d") { [pscustomobject]@{ Version = (Ver $v); Raw = $v; Path = $_.FullName } }
    }
}
function Newest($artifact) { Jars $artifact | Sort-Object Version -Descending | Select-Object -First 1 }
function Exact($artifact, $raw) { (Jars $artifact | Where-Object { $_.Raw -eq $raw } | Select-Object -First 1).Path }

# LWJGL 3: oldest version present for core + glfw + opengl together (the API every supported game has)
$lw = $null
foreach ($c in (Jars "lwjgl" | Where-Object { $_.Version.Major -eq 3 } | Sort-Object Version)) {
    $g = Exact "lwjgl-glfw" $c.Raw; $o = Exact "lwjgl-opengl" $c.Raw
    if ($g -and $o) { $lw = @($c.Path, $g, $o); break }
}
$lw2 = (Jars "lwjgl" | Where-Object { $_.Version.Major -eq 2 } | Sort-Object Version -Descending | Select-Object -First 1).Path
# SDL3 platform (26.3+): lwjgl-sdl and the LWJGL core of the same version
$sdlJar = Newest "lwjgl-sdl"
$sdl = $sdlJar.Path
$sdlCore = if ($sdlJar) { Exact "lwjgl" $sdlJar.Raw }
# ASM: one version for all four (newest core that has the others too)
$asm = $null
foreach ($c in (Jars "asm" | Sort-Object Version -Descending)) {
    $parts = @($c.Path, (Exact "asm-commons" $c.Raw), (Exact "asm-tree" $c.Raw), (Exact "asm-analysis" $c.Raw))
    if (($parts | Where-Object { -not $_ }).Count -eq 0) { $asm = $parts; $asmVer = $c.Version; break }
}
if (-not $lw -or -not $asm -or -not $lw2 -or -not $sdl -or -not $sdlCore) { throw "Missing jars; launch Minecraft 1.21.x with Fabric, 1.8.9 and 26.3 once so the launcher downloads them." }
# Java 25 class files (26.x) need ASM 9.8+: an older one makes the nametag hook fail at runtime
if ($asmVer -lt [version]"9.8") { Write-Warning "ASM $asmVer is older than 9.8: the nametag hook cannot read Java 25 classes (26.x)." }
Write-Host "inputs: LWJGL" (Split-Path $lw[0] -Leaf) "| ASM $asmVer |" (Split-Path $lw2 -Leaf) "|" (Split-Path $sdl -Leaf)

$final = if ($Out) { [IO.Path]::GetFullPath($Out) } else { Join-Path $root "build" }
$out = "$final.new"; $cls = Join-Path $out "classes"; $tool = Join-Path $out "tool"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $cls, $tool | Out-Null
& javac -encoding UTF-8 -cp ($asm -join ";") -d $tool (Join-Path $root "tools\Relocate.java"); if ($LASTEXITCODE -ne 0) { throw "tool compile failed" }
& java -cp ("$tool;" + ($asm -join ";")) Relocate $cls $asm[0]; if ($LASTEXITCODE -ne 0) { throw "relocate failed" }
$all = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java
$legacy = @($all | Where-Object { $_.Name -in @("PlatLwjgl2.java", "GlCompat.java") })
$sdlSrc = @($all | Where-Object { $_.Name -eq "PlatSdl.java" })
$main = @($all | Where-Object { $_.Name -notin @("PlatLwjgl2.java", "GlCompat.java", "PlatSdl.java") })
& javac --release 8 -encoding UTF-8 -Xlint:-options -cp ("$cls;" + ($lw -join ";")) -d $cls $main.FullName
if ($LASTEXITCODE -ne 0) { throw "javac (LWJGL3 pass) failed" }
& javac --release 8 -encoding UTF-8 -Xlint:-options -cp "$cls;$lw2" -d $cls $legacy.FullName
if ($LASTEXITCODE -ne 0) { throw "javac (LWJGL2 pass) failed" }
& javac --release 8 -encoding UTF-8 -Xlint:-options -cp "$cls;$sdl;$sdlCore" -d $cls $sdlSrc.FullName
if ($LASTEXITCODE -ne 0) { throw "javac (SDL pass) failed" }
# module/settings schema for the launcher's Client page (UTF-8, no BOM)
$schema = & java -cp ("$cls;" + ($lw -join ";")) dev.nether.ov.Schema
if ($LASTEXITCODE -ne 0 -or -not $schema) { throw "schema dump failed" }
[IO.File]::WriteAllText((Join-Path $out "schema.json"), ($schema -join ""))
# overlay class list (the agent defines them inside the LWJGL loader) + fonts
$ov = Get-ChildItem (Join-Path $cls "dev\nether\ov") -Filter *.class | ForEach-Object { "dev.nether.ov." + $_.BaseName }
Set-Content (Join-Path $cls "ov.list") $ov -Encoding ascii
Copy-Item (Join-Path $root "res\fonts") (Join-Path $cls "fonts") -Recurse
Copy-Item (Join-Path $root "res\names") (Join-Path $cls "names") -Recurse
# brand art (made from art/ by the launcher's asset step: fireball + NETHER CLIENT wordmark)
Copy-Item (Join-Path $root "res\brand") (Join-Path $cls "brand") -Recurse
# line icons (res/icons/<id>.svg, read by ov/ModIcons)
Copy-Item (Join-Path $root "res\icons") (Join-Path $cls "icons") -Recurse
# Hook must sit on the bootstrap class path so every loader (incl. Fabric Knot) can see it
$boot = Join-Path $out "boot\dev\nether\agent"; New-Item -ItemType Directory -Force $boot | Out-Null
Move-Item (Join-Path $cls "dev\nether\agent\Hook*.class"), (Join-Path $cls "dev\nether\agent\Tiny*.class") $boot # Tiny: Legacy Fabric 1.8.9 names, shared by the agent and the overlay
& jar cf (Join-Path $out "nether-agent-boot.jar") -C (Join-Path $out "boot") .
if ($LASTEXITCODE -ne 0) { throw "boot jar failed" }
Set-Content (Join-Path $out "manifest.txt") "Premain-Class: dev.nether.agent.Agent`nBoot-Class-Path: nether-agent-boot.jar`nCan-Set-Native-Method-Prefix: true`n" -Encoding ascii
& jar cfm (Join-Path $out "nether-agent.jar") (Join-Path $out "manifest.txt") -C $cls .
if ($LASTEXITCODE -ne 0) { throw "agent jar failed" }
# success: swap the finished build in (a failed build above leaves the previous one untouched)
if (Test-Path $final) { Remove-Item $final -Recurse -Force }
Move-Item $out $final
Write-Host "built" (Join-Path $final "nether-agent.jar")
