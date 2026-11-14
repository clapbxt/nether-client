@echo off
rem Nether Client multitool: run, build, test, publish. Double-click it or run "nether" from the repo root.
setlocal
title Nether Client tools
cd /d "%~dp0"
for /f %%e in ('echo prompt $E^| cmd') do set "ESC=%%e"
set "R=%ESC%[38;2;230;32;48m"
set "G=%ESC%[38;2;150;160;172m"
set "W=%ESC%[97m"
set "X=%ESC%[0m"

:menu
cls
echo.
echo   %R%NETHER CLIENT%X%  %G%tools%X%
echo   %G%------------------------------------------------------------%X%
echo   %G%DEV%X%
echo     %W%1%X%  Run launcher           %G%debug build, quickest to compile%X%
echo     %W%2%X%  Run launcher           %G%release build, what players get%X%
echo     %W%3%X%  Build in-game client   %G%agent\build, bundled into the launcher%X%
echo     %W%4%X%  Test in-game client    %G%offline dev game, no account needed%X%
echo     %W%5%X%  Run tests
echo   %G%BUILD%X%
echo     %W%6%X%  Build everything       %G%agent + launcher + installer (release)%X%
echo   %G%PUBLISH%X%
echo     %W%S%X%  Content studio         %G%browser: see, upload, remove, publish capes + cosmetics%X%
echo     %W%7%X%  Publish content        %G%sign + deploy everything in backend\content as it is%X%
echo     %W%8%X%  Commit and push        %G%to github.com/clapbxt/nether-client%X%
echo     %W%9%X%  Make a release         %G%versioned installer, optional GitHub release%X%
echo   %G%OTHER%X%
echo     %W%T%X%  Check tools and sign-ins
echo     %W%Q%X%  Quit
echo.
set "c="
set /p "c=  Press 1-9, S, T or Q, then Enter: "
set "go="
if "%c%"=="1" set "go=run_debug"
if "%c%"=="2" set "go=run_release"
if "%c%"=="3" set "go=agent"
if "%c%"=="4" set "go=test_game"
if "%c%"=="5" set "go=tests"
if "%c%"=="6" set "go=build_all"
if "%c%"=="7" set "go=content"
if "%c%"=="8" set "go=push"
if "%c%"=="9" set "go=release"
if /i "%c%"=="s" set "go=studio"
if /i "%c%"=="t" set "go=tools"
if /i "%c%"=="q" exit /b 0
if not defined go goto menu
echo.
call :%go%
if errorlevel 1 (echo. & echo   %R%FAILED%X% - see the output above.) else (echo. & echo   %W%Done.%X%)
echo.
pause
goto menu

rem ---------------------------------------------------------------- dev

:run_debug
cargo run -p nether-app
exit /b

:run_release
cargo run -p nether-app --release
exit /b

:agent
call :find_javac || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File agent\build.ps1
if errorlevel 1 exit /b 1
echo   %G%Rebuild the launcher (1, 2 or 6) to bundle it.%X%
exit /b 0

:test_game
set "ver=1.21.11"
set /p "ver=  Minecraft version [1.21.11]: "
set "secs=600"
set /p "secs=  Close after how many seconds [600]: "
set "fab="
set /p "fab=  With Fabric? (y/N): "
set "fabarg="
if /i "%fab%"=="y" set "fabarg=fabric"
set "NETHER_DEV_OFFLINE=1"
cargo run -p nether-core --release --features dev --example launch -- %ver% Player %secs% %fabarg%
set "rc=%errorlevel%"
set "NETHER_DEV_OFFLINE="
exit /b %rc%

:tests
cargo test --workspace
exit /b

rem ---------------------------------------------------------------- build

:build_all
call :agent
if errorlevel 1 if not exist agent\build\nether-agent.jar exit /b 1
if errorlevel 1 echo   %G%In-game client not rebuilt; using the previous agent\build.%X%
cargo build -p nether-app --release || exit /b 1
cargo build -p nether-setup --release || exit /b 1
echo.
echo   Launcher:  target\release\nether-app.exe
echo   Installer: target\release\NetherClientSetup.exe
exit /b 0

rem ---------------------------------------------------------------- publish

:content
echo   Checks and signs backend\content, then deploys it with the cape API to Cloudflare.
echo   %G%Asks for your content key passphrase. Run new migrations in backend\worker\migrations first.%X%
echo   %G%To add or remove capes and cosmetics with previews, use S (Content studio) instead.%X%
echo   %G%How to add news, capes and packs: backend\content\README.md%X%
call :confirm "Publish content now?" || exit /b 0
node backend\content\sign.mjs sign || exit /b 1
pushd backend\worker
call npx wrangler deploy
set "rc=%errorlevel%"
popd
if not "%rc%"=="0" exit /b %rc%
echo.
echo   Live. Launchers pick it up the next time they refresh.
echo   %G%Commit the new manifest.json / manifest.sig too (option 8).%X%
exit /b 0

:studio
echo   %W%Content studio%X%
echo   %G%Your browser opens it. If not, open the link printed below (it holds the access key: don't share it).%X%
echo     - Left: everything there is. Click one to see it on your skin (drag to turn, scroll to zoom).
echo     - Upload: drop a Blockbench .bbmodel / Java .json + PNGs, a cape PNG, or an aura .json + .png.
echo     - Tick items, then Remove. Edit name / rarity / description under the preview.
echo     - Publish: content key passphrase, tick that migrations are run, type PUBLISH. Goes live for everyone.
echo     - Blockbench templates: tools\cosmetics	emplates
echo   %G%Keep this window open while you use it. Ctrl+C here stops it.%X%
echo.
node tools\studio\server.mjs
exit /b 0

:push
git status --short
echo.
set "msg="
set /p "msg=  Commit message (empty = cancel): "
if not defined msg exit /b 0
git add -A || exit /b 1
git commit -m "%msg%"
if errorlevel 1 echo   %G%Nothing new to commit; pushing what is there.%X%
git push -u origin HEAD
exit /b

:release
echo   Builds and signs dist\NetherClientSetup-^<version^>.exe. Publishing also commits the version bump,
echo   tags it, pushes, and creates the GitHub release that installed launchers update from.
echo   %G%Commit your work first (option 8).%X%
echo.
set "v="
set /p "v=  New version, e.g. 0.2.0 (empty = cancel): "
if not defined v exit /b 0
call :confirm "Publish it to GitHub Releases too?"
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\release.ps1 -Version %v%
    exit /b
)
set "notes="
set /p "notes=  Release notes, one line (optional): "
powershell -NoProfile -ExecutionPolicy Bypass -File tools\release.ps1 -Version %v% -Publish -Notes "%notes%"
exit /b

rem ---------------------------------------------------------------- other

:tools
call :find_javac >nul 2>&1
call :probe "Rust"   "cargo --version" "rustup.rs"
call :probe "JDK"    "javac -version"  "needed for the in-game client"
call :probe "Node"   "node --version"  "nodejs.org"
call :probe "Git"    "git --version"   "git-scm.com"
set "GH=gh"
where gh >nul 2>&1 || if exist "%LOCALAPPDATA%\Programs\gh\gh.exe" set "GH=%LOCALAPPDATA%\Programs\gh\gh.exe"
"%GH%" auth status >nul 2>&1 && (call :show "GitHub CLI" "signed in") || call :show "GitHub CLI" "%R%not signed in%X% - winget install GitHub.cli, then gh auth login"
if exist "%APPDATA%\xdg.config\.wrangler\config\default.toml" (call :show "Cloudflare" "signed in") else (call :show "Cloudflare" "%R%not signed in%X% - npx wrangler login")
if exist "%USERPROFILE%\.nether-signing\release-ed25519.key" (call :show "Release key" "found") else (call :show "Release key" "%R%missing%X% - %USERPROFILE%\.nether-signing")
if exist "%USERPROFILE%\.nether-signing\content-ed25519.key" (call :show "Content key" "found") else (call :show "Content key" "%R%missing%X% - %USERPROFILE%\.nether-signing")
if exist "%APPDATA%\NetherClient\libraries" (call :show "Game libs" "found") else (call :show "Game libs" "%R%missing%X% - launch 1.21.x Fabric, 1.8.9 and 26.3 once")
exit /b 0

rem :probe "label" "command" "where to get it" -> first line of the command's output, or missing
:probe
set "s="
for /f "delims=" %%v in ('%~2 2^>nul') do if not defined s set "s=%%v"
if defined s (call :show "%~1" "%s%") else (call :show "%~1" "%R%missing%X% - %~3")
exit /b 0

:show
set "lbl=%~1             "
echo   %G%%lbl:~0,13%%X%%~2
exit /b 0

rem ---------------------------------------------------------------- helpers

rem javac on PATH, else the JDK the launcher downloaded (%APPDATA%\NetherClient\runtimes)
:find_javac
where javac >nul 2>&1 && exit /b 0
set "NC_JDK="
for /f "delims=" %%j in ('dir /s /b "%APPDATA%\NetherClient\runtimes\javac.exe" 2^>nul') do if not defined NC_JDK set "NC_JDK=%%~dpj"
if not defined NC_JDK (echo   %R%No JDK found.%X% Install one, or launch a game once so the launcher downloads it. & exit /b 1)
set "PATH=%NC_JDK%;%PATH%"
exit /b 0

rem :confirm "question" -> errorlevel 0 on y
:confirm
set "yn="
set /p "yn=  %~1 (y/N): "
if /i "%yn%"=="y" exit /b 0
exit /b 1
