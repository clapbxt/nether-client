<#
Builds, signs and (with -Publish) publishes a Nether Client release. Installed launchers pick it up by themselves:
they download NetherClientSetup-<version>.exe, check its .sig against the key embedded in the launcher, and install it
when the user closes Nether.

  powershell -File tools\release.ps1 -Version 0.2.0                     build + sign into dist\ (nothing published)
  powershell -File tools\release.ps1 -Version 0.2.0 -Publish -Notes "…"  also commit + tag, and create the GitHub release

Needs: Rust (MSVC), Node 18+, the release key (%USERPROFILE%\.nether-signing\release-ed25519.key, from
`node tools\release-sign.mjs keygen`), and for -Publish the GitHub CLI signed in (`gh auth login`).
Releases go to the public repo clapbxt/nether-client (see crates/core/src/update.rs).
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$Publish
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$repo = "clapbxt/nether-client"

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 1.2.3" }
$toml = "crates\app\Cargo.toml"
$text = Get-Content $toml -Raw
$current = [regex]::Match($text, '(?m)^version = "([^"]+)"').Groups[1].Value
if ([version]$Version -le [version]$current) { throw "Version $Version must be newer than the current $current" }

function Find-Gh {
    $g = Get-Command gh -ErrorAction SilentlyContinue
    if ($g) { return $g.Source }
    $p = Join-Path $env:LOCALAPPDATA "Programs\gh\gh.exe"
    if (Test-Path $p) { return $p }
    throw "GitHub CLI not found. Install it (winget install GitHub.cli) and run: gh auth login"
}
if ($Publish) { $gh = Find-Gh; & $gh auth status | Out-Null; if ($LASTEXITCODE -ne 0) { throw "gh is not signed in: run gh auth login" } }

Write-Host "== Nether Client $current -> $Version"
$text = [regex]::Replace($text, '(?m)^version = "[^"]+"', "version = `"$Version`"", 1)
Set-Content $toml $text -NoNewline -Encoding utf8

# the in-game client is embedded in the launcher: rebuild it when this PC can (needs the game's LWJGL jars)
Write-Host "== agent"
# javac: the PATH's JDK, else the JDK-shaped Java runtime the launcher itself downloaded
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    $rt = Get-ChildItem (Join-Path $env:APPDATA "NetherClient\runtimes") -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($rt) { $env:Path = $rt.DirectoryName + ";" + $env:Path }
}
try { powershell -NoProfile -ExecutionPolicy Bypass -File agent\build.ps1 | Out-Host; if ($LASTEXITCODE -ne 0) { throw "agent build failed" } }
catch {
    if (-not (Test-Path agent\build\nether-agent.jar)) { throw "The in-game client can't be built and there is no previous build: $_" }
    Write-Warning "Agent not rebuilt ($_). Using the existing agent\build."
}

Write-Host "== launcher"
cargo build -p nether-app --release
if ($LASTEXITCODE -ne 0) { throw "launcher build failed" }
Write-Host "== setup"
cargo build -p nether-setup --release
if ($LASTEXITCODE -ne 0) { throw "setup build failed" }

New-Item -ItemType Directory -Force dist | Out-Null
$name = "NetherClientSetup-$Version.exe"
Copy-Item target\release\NetherClientSetup.exe "dist\$name" -Force
node tools\release-sign.mjs sign "dist\$name"
if ($LASTEXITCODE -ne 0) { throw "signing failed" }
$size = [math]::Round((Get-Item "dist\$name").Length / 1MB, 1)
Write-Host "== built dist\$name ($size MB) + .sig"

if ($Publish) {
    git add $toml Cargo.lock
    git commit -m "Release $Version" | Out-Host
    git tag "v$Version"
    if ($Notes -eq "") { $Notes = "Nether Client $Version" }
    & $gh release create "v$Version" "dist\$name" "dist\$name.sig" --repo $repo --title "Nether Client $Version" --notes $Notes
    if ($LASTEXITCODE -ne 0) { throw "GitHub release failed (the build is still in dist\)" }
    Write-Host "== published: https://github.com/$repo/releases/tag/v$Version"
} else {
    Write-Host "Not published. Run again with -Publish, or upload dist\$name and dist\$name.sig to a release in $repo."
}
