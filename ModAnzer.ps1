<#
.SYNOPSIS
    ModAnzer - finds every Minecraft mods folder on this PC and checks the mods in it.

.DESCRIPTION
    Based on HabibiModAnalyzer by HadronCollision
    (https://github.com/HadronCollision/PowershellScripts).

    What it does:
      * Auto-detects mods folders for vanilla .minecraft, CurseForge, Modrinth App,
        Prism / PolyMC / MultiMC, ATLauncher, GDLauncher, Technic, Dawn (Feather), Lunar,
        and any "dot" client folder (.badlion, ...) - no path pasting needed.
      * Also picks up the game folder of any Minecraft that is running right now.
      * Hashes each .jar (SHA1) and looks the hashes up on Modrinth (batched) and
        Megabase and CurseForge. Only hashes/fingerprints are sent - never your files.
      * Mods that are not recognised are opened in memory (no temp extraction) and
        searched, including nested jars, for well-known cheat module names.

    Read-only: nothing is modified, uploaded, or installed.

.PARAMETER Path
    One or more folders to scan instead of auto-detection.

.PARAMETER Offline
    Skip the online lookups and only do the cheat-string scan.

.PARAMETER SkipIntro
    Don't show the "what this script does" notice or wait for Enter.

.PARAMETER Mode
    'mods' for the Mod Analyzer or 'injection' for the Injection Checker,
    skipping the start menu.

.PARAMETER LiveSeconds
    Injection Checker: stop the live watch after this many seconds
    (default: keep watching until you press Q).

.PARAMETER NoPause
    Don't wait for Enter at the end (by default it waits, so the window
    stays open when started with right-click > Run with PowerShell).

.EXAMPLE
    .\ModAnzer.ps1
.EXAMPLE
    .\ModAnzer.ps1 -Path "D:\Games\MyPack\mods"
#>
[CmdletBinding()]
param(
    [string[]]$Path,
    [switch]$Offline,
    [switch]$SkipIntro,
    [switch]$NoPause,
    [string]$Mode = '',
    [int]$LiveSeconds = 0
)

$ErrorActionPreference = 'Stop'

# If anything goes wrong, show the error and keep the window open instead of closing.
trap {
    Write-Host
    Write-Host 'ModAnzer stopped because of an error:' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ("(line {0})" -f $_.InvocationInfo.ScriptLineNumber) -ForegroundColor DarkGray
    if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
}
$ProgressPreference    = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------------------------------------------------------------- settings

# Strong names: unusual words that are basically only used by cheats.
# Matched case-sensitively anywhere in a mod's files.
$CheatStrings = @(
    'AnchorTweaks', 'AutoAnchor', 'AutoCrystal', 'AutoDoubleHand', 'AutoHitCrystal',
    'AutoPot', 'AutoTotem', 'InventoryTotem', 'JumpReset', 'LegitTotem', 'PingSpoof',
    'SelfDestruct', 'ShieldBreaker', 'TriggerBot', 'AxeSpam', 'WebMacro',
    'KillAura', 'CrystalAura', 'AnchorAura', 'AutoClicker'
)
$CheatRegex = [regex]::new(($CheatStrings | ForEach-Object { [regex]::Escape($_) }) -join '|')

# Weak names: also ordinary game-code words (getVelocity, renderHitboxes, controller
# aim assist...). They only count when they are a whole name on their own: a class
# file called Velocity.class / VelocityModule.class, or the exact text "Velocity"
# stored as its own string in a class file - which is how cheat module names look.
$WeakCheatStrings = @('Velocity', 'Hitboxes', 'AimAssist', 'AutoArmor', 'FastPlace')
$weakAlt = ($WeakCheatStrings | ForEach-Object { [regex]::Escape($_) }) -join '|'
$WeakNameRegex = [regex]::new("(?:^|/)($weakAlt)(?:Module|Hack|Feature|Mod)?(?:\$[^/]*)?\.class$")
# CONSTANT_Utf8 entry in a class file: tag 0x01, 2-byte length, then the text.
$WeakConstRegex = [regex]::new('\x01\x00(?:' + (($WeakCheatStrings | ForEach-Object { [regex]::Escape([string][char]$_.Length) + [regex]::Escape($_) }) -join '|') + ')(?![\w])')

# Mods known to be safe even though their code contains a cheat-like name (for example a
# HUD that shows "AutoTotem" status). Matched by exact SHA1 so a renamed cheat can't
# pretend to be one of them. Add entries as  'sha1-in-lowercase' = 'Name'
# (the SHA1 is printed under every [CHEAT] result).
$TrustedMods = @{
    '1807bd0592525fe40d0d0ee69d30b7486c48fdc7' = 'FastClient HUD 1.21.11'   # contains Hitboxes/Velocity HUD names
    '34911f6d85906b40ca45e7750c65b713955451d2' = 'Dawn Client'              # contains Hitboxes/Velocity HUD names
}

# Extra trusted mods are also read from this file in the GitHub repo, so new
# versions can be added by editing trusted.txt on GitHub (no script change needed).
$TrustedListUrl = 'https://raw.githubusercontent.com/napatc1/ModAnzer/main/trusted.txt'

# CurseForge lookups go through ModAnzer's small server, which keeps the CurseForge
# API key private (source: supabase/functions/modanzer-cf in the repo). Only CurseForge
# file fingerprints (numbers) are sent.
$CurseForgeProxyUrl = 'https://reumqqmnuvabnpmbstnu.supabase.co/functions/v1/modanzer-cf'

# Optional: your own CurseForge API key (free at https://console.curseforge.com) to ask
# CurseForge directly instead. Can also be set with the MODANZER_CF_KEY environment variable.
$CurseForgeApiKey = ''

# Folder names that hold mods
$ModFolderNames = @('mods', 'user-mods')

# Dot-folders that are never Minecraft and are expensive to walk
$SkipDirs = @(
    '.git', '.gradle', '.m2', '.npm', '.nuget', '.cargo', '.rustup', '.android',
    '.vscode', '.vscode-server', '.cache', '.docker', '.conda', '.ipython',
    '.dotnet', '.templateengine', 'node_modules', 'assets', 'libraries',
    'versions', 'logs', 'crash-reports', 'screenshots', 'saves', 'resourcepacks',
    'shaderpacks', 'natives', 'jre', 'runtime', 'java'
)

$MaxDepth       = 6          # how deep to look for a mods folder under each root
$MaxEntryBytes  = 8MB        # skip unusually large entries inside a jar
$UserAgent      = 'ModAnzer (github.com/napatc1/ModAnzer)'

# ---------------------------------------------------------------- helpers

function Write-Section($text) {
    Write-Host "{ $text }" -ForegroundColor DarkCyan
}

function Get-ShortPath([string]$p) {
    foreach ($pair in @(@($env:APPDATA, '%APPDATA%'), @($env:LOCALAPPDATA, '%LOCALAPPDATA%'), @($env:USERPROFILE, '~'))) {
        if ($pair[0] -and $p.StartsWith($pair[0], [StringComparison]::OrdinalIgnoreCase)) {
            return $pair[1] + $p.Substring($pair[0].Length)
        }
    }
    return $p
}

function Get-LauncherName([string]$p) {
    $l = $p.Replace('/', '\').ToLowerInvariant()
    switch -Regex ($l) {
        'curseforge|\\twitch\\'      { return 'CurseForge' }
        'modrinthapp|theseus'        { return 'Modrinth' }
        'prismlauncher'              { return 'Prism' }
        'polymc'                     { return 'PolyMC' }
        'multimc'                    { return 'MultiMC' }
        'atlauncher'                 { return 'ATLauncher' }
        'gdlauncher'                 { return 'GDLauncher' }
        '\\\.technic\\'              { return 'Technic' }
        '\\\.(dawn|feather)\\'       { return 'Dawn' }
        '\\\.lunarclient\\'          { return 'Lunar' }
        'fastclient'                 { return 'Fast Client' }
        '\\\.tlauncher\\'            { return 'TLauncher' }
        '\\\.minecraft\\'            { return 'Minecraft' }
        '\\\.([^\\]+)\\'             { return ".$($Matches[1])" }
    }
    return 'Custom'
}

# Walk a root looking for folders named mods / user-mods, without descending into junk.
function Find-ModFolders([string]$root, [int]$depth = 0) {
    if ($depth -gt $MaxDepth) { return }
    $children = $null
    try { $children = [IO.Directory]::GetDirectories($root) } catch { return }
    foreach ($dir in $children) {
        $name = [IO.Path]::GetFileName($dir)
        if ($ModFolderNames -contains $name.ToLowerInvariant()) {
            $dir
            continue
        }
        if ($SkipDirs -contains $name.ToLowerInvariant()) { continue }
        try {
            if (([IO.File]::GetAttributes($dir) -band [IO.FileAttributes]::ReparsePoint)) { continue }
        } catch { continue }
        Find-ModFolders $dir ($depth + 1)
    }
}

# Game directories of any Minecraft that is running right now (--gameDir argument).
function Get-RunningGameDirs {
    $result = @()
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction Stop
    } catch { return $result }
    foreach ($p in $procs) {
        if ($p.CommandLine -match '--gameDir\s+(?:"([^"]+)"|(\S+))') {
            $dir = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            $result += [PSCustomObject]@{ Pid = $p.ProcessId; Dir = $dir; Started = $p.CreationDate }
        }
    }
    return $result
}

function Get-ZoneIdentifier([string]$filePath) {
    try {
        $ads = Get-Content -Raw -Stream Zone.Identifier -LiteralPath $filePath -ErrorAction Stop
        if ($ads -match 'HostUrl=(.+)') { return $Matches[1].Trim() }
    } catch {}
    return $null
}

function Read-EntryBytes([System.IO.Compression.ZipArchiveEntry]$entry) {
    $ms = New-Object IO.MemoryStream
    $s = $entry.Open()
    try { $s.CopyTo($ms) } finally { $s.Dispose() }
    return $ms.ToArray()
}

# Search a jar (from a stream) for cheat strings: entry names, then file contents, then nested jars.
function Search-Jar([IO.Stream]$stream, [string]$label, [System.Collections.Generic.List[object]]$hits, [int]$level = 0) {
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    $zip = $null
    try { $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Read, $true) }
    catch { return }
    try {
        $found = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($entry in $zip.Entries) {
            foreach ($m in $CheatRegex.Matches($entry.FullName)) { [void]$found.Add($m.Value) }
            $wm = $WeakNameRegex.Match($entry.FullName)
            if ($wm.Success) { [void]$found.Add($wm.Groups[1].Value) }

            if ($entry.Length -eq 0 -or $entry.Length -gt $MaxEntryBytes) { continue }
            $lower = $entry.FullName.ToLowerInvariant()

            if ($lower.EndsWith('.jar') -and $level -lt 3) {
                $bytes = Read-EntryBytes $entry
                $inner = New-Object IO.MemoryStream(,$bytes)
                Search-Jar $inner "$label -> $([IO.Path]::GetFileName($entry.FullName))" $hits ($level + 1)
                $inner.Dispose()
                continue
            }
            if ($lower.EndsWith('.class') -or $lower.EndsWith('.json') -or $lower.EndsWith('.toml') -or $lower.EndsWith('.mf')) {
                $text = $latin1.GetString((Read-EntryBytes $entry))
                foreach ($m in $CheatRegex.Matches($text)) { [void]$found.Add($m.Value) }
                if ($lower.EndsWith('.class')) {
                    foreach ($m in $WeakConstRegex.Matches($text)) { [void]$found.Add($m.Value.Substring(3)) }
                }
            }
        }
        if ($found.Count -gt 0) {
            $hits.Add([PSCustomObject]@{ Where = $label; Strings = (@($found) | Sort-Object) })
        }
    } finally { $zip.Dispose() }
}

# CurseForge fingerprint: MurmurHash2 (seed 1) of the file with tab/LF/CR/space bytes removed.
function Get-CurseForgeFingerprint([string]$path) {
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    $data = $latin1.GetBytes(($latin1.GetString([IO.File]::ReadAllBytes($path)) -replace "[\t\n\r ]", ''))
    $mask = [uint64]4294967295
    $m = [uint64]0x5bd1e995
    $len = $data.Length
    $h = ([uint64]1 -bxor [uint64]$len) -band $mask
    $i = 0
    while ($len - $i -ge 4) {
        $k = [uint64][BitConverter]::ToUInt32($data, $i)
        $k = ($k * $m) -band $mask
        $k = $k -bxor ($k -shr 24)
        $k = ($k * $m) -band $mask
        $h = (($h * $m) -band $mask) -bxor $k
        $i += 4
    }
    $rest = $len - $i
    if ($rest -ge 3) { $h = $h -bxor ([uint64]$data[$i + 2] -shl 16) }
    if ($rest -ge 2) { $h = $h -bxor ([uint64]$data[$i + 1] -shl 8) }
    if ($rest -ge 1) { $h = (($h -bxor [uint64]$data[$i]) * $m) -band $mask }
    $h = $h -bxor ($h -shr 13)
    $h = ($h * $m) -band $mask
    $h = $h -bxor ($h -shr 15)
    return [uint32]$h
}

# Mods the CurseForge app recorded as installed (minecraftinstance.json next to the mods folder).
function Get-CurseForgeRecords([string]$modsFolder) {
    $map = @{}
    $json = Join-Path (Split-Path -Parent $modsFolder) 'minecraftinstance.json'
    if (-not (Test-Path -LiteralPath $json)) { return $map }
    try {
        $inst = Get-Content -Raw -LiteralPath $json | ConvertFrom-Json
        foreach ($a in @($inst.installedAddons)) {
            $f = $a.installedFile
            if ($f -and $f.fileName -and $a.name) {
                $map[[string]$f.fileName] = [PSCustomObject]@{ Name = [string]$a.name; Length = $f.fileLength }
            }
        }
    } catch {}
    return $map
}

function Wait-BeforeClose {
    if (-not $NoPause) {
        Write-Host
        Read-Host 'Press Enter to close' | Out-Null
    }
}

function Invoke-Json([string]$uri, [string]$method = 'Get', $body = $null) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; UserAgent = $UserAgent; TimeoutSec = 20 }
    if ($body) { $params.Body = $body; $params.ContentType = 'application/json' }
    return Invoke-RestMethod @params
}

# ---------------------------------------------------------------- 1. find folders

Clear-Host
Write-Host 'ModAnzer' -ForegroundColor Yellow -NoNewline
Write-Host '  - Minecraft mod analyzer + injection checker (based on HabibiModAnalyzer by HadronCollision)' -ForegroundColor DarkGray
Write-Host

if (-not $SkipIntro) {
    Write-Host 'This is NOT a hacking or malware tool. It is read-only and only:' -ForegroundColor White
    Write-Host '  Mod Analyzer' -ForegroundColor White
    Write-Host '  - Finds your Minecraft mods folders (or the one you choose)' -ForegroundColor Gray
    Write-Host '  - Makes a fingerprint (SHA1 hash) of each .jar mod file' -ForegroundColor Gray
    Write-Host '  - Looks those fingerprints up on Modrinth, Megabase and CurseForge to name your mods' -ForegroundColor Gray
    Write-Host '  - Searches mods it does not recognise for known cheat names' -ForegroundColor Gray
    Write-Host '  Injection Checker' -ForegroundColor White
    Write-Host '  - Reads how the running Minecraft was started (its launch options)' -ForegroundColor Gray
    Write-Host '  - Lists the DLL files loaded into Minecraft and watches for new ones' -ForegroundColor Gray
    Write-Host '  - Watches the mods folder and newly started programs while it runs' -ForegroundColor Gray
    Write-Host '  Both show the results on this screen only.' -ForegroundColor Gray
    Write-Host
    Write-Host 'It does NOT:' -ForegroundColor White
    Write-Host '  - Upload your files, or send anything except the mod fingerprints' -ForegroundColor Gray
    Write-Host '  - Read passwords, tokens, browser data, Discord or launcher accounts' -ForegroundColor Gray
    Write-Host '  - Read or change the game''s memory, or interfere with any program' -ForegroundColor Gray
    Write-Host '  - Change, move or delete any files' -ForegroundColor Gray
    Write-Host '  - Install anything or change any settings' -ForegroundColor Gray
    Write-Host
    Write-Host 'Source code: https://github.com/napatc1/ModAnzer' -ForegroundColor DarkGray
    Write-Host
    Read-Host 'Press Enter to start (or Ctrl+C to quit)' | Out-Null
    Write-Host
}

# ---------------------------------------------------------------- injection checker

function Get-MinecraftProcesses {
    $list = @()
    $cim = $null
    try { $cim = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction Stop) } catch {}
    if ($null -ne $cim) {
        foreach ($p in $cim) {
            $list += [PSCustomObject]@{ Pid = [int]$p.ProcessId; Path = [string]$p.ExecutablePath; CommandLine = [string]$p.CommandLine; Started = $p.CreationDate }
        }
    } else {
        foreach ($p in @(Get-Process -Name 'javaw', 'java' -ErrorAction SilentlyContinue)) {
            $list += [PSCustomObject]@{ Pid = $p.Id; Path = [string]$p.Path; CommandLine = [string]$p.CommandLine; Started = $p.StartTime }
        }
    }
    return @($list | Where-Object { $_.CommandLine -match 'minecraft|fabric|forge|quilt|lunar|feather|dawn|--gameDir|--accessToken' })
}

function Get-ProcessModuleFiles([int]$procId) {
    try {
        $p = Get-Process -Id $procId -ErrorAction Stop
        return @($p.Modules | ForEach-Object { $_.FileName } | Where-Object { $_ } | Select-Object -Unique)
    } catch { return $null }
}

$script:SignerCache = @{}
function Get-Signer([string]$file) {
    if ($script:SignerCache.ContainsKey($file)) { return $script:SignerCache[$file] }
    $r = $null
    try {
        $sig = Get-AuthenticodeSignature -LiteralPath $file -ErrorAction Stop
        if ($sig.Status -eq 'Valid' -and $sig.SignerCertificate) {
            $r = $sig.SignerCertificate.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false)
        }
    } catch {}
    $script:SignerCache[$file] = $r
    return $r
}

function Test-UnderPath([string]$file, [string[]]$roots) {
    foreach ($r in $roots) {
        if ($r -and $file.StartsWith($r.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

# Folders that normal programs aren't run or loaded from
function Get-UserDropFolders {
    $h = $env:USERPROFILE
    return @("$h\Downloads", "$h\Desktop", "$h\Documents", $env:TEMP, "$env:LOCALAPPDATA\Temp", "$h\Videos", "$h\Music", "$h\Pictures") |
        Where-Object { $_ } | ForEach-Object { $_.Replace('\', [IO.Path]::DirectorySeparatorChar) } | Select-Object -Unique
}

# Classify one DLL loaded into Minecraft. Returns $null when it's a normal Windows/Java/game file.
function Get-ModuleVerdict([string]$file, [string]$javaHome) {
    if (-not [IO.Path]::IsPathRooted($file)) { return $null }
    $name = [IO.Path]::GetFileName($file).ToLowerInvariant()
    if ($name -match '^(lib)?instrument\.(dll|so|dylib)$') {
        return [PSCustomObject]@{ Level = 'Red'; Text = 'Java agent support (instrument) is loaded - a Java agent was added at launch or attached to the running game' }
    }
    if ($name -match '^(lib)?jdwp\.(dll|so|dylib)$') {
        return [PSCustomObject]@{ Level = 'Red'; Text = 'Java debugger (JDWP) is loaded - lets other programs change the game while it runs' }
    }
    $sysRoots = @($env:SystemRoot, $env:windir, ${env:ProgramFiles}, ${env:ProgramFiles(x86)}, $javaHome, '/usr/lib', '/lib', '/usr/lib64', '/lib64')
    $drop = Get-UserDropFolders
    $inDrop = Test-UnderPath $file $drop
    # LWJGL / game natives that Minecraft itself unpacks (often into Temp)
    $isGameNative = ($name -match '^(lib)?(lwjgl|glfw|openal|jemalloc|jinput|jnidispatch|sqlite|discord[-_]?(rpc|game-sdk)|flite|tinyfd|stb|opengl|opus|rnnoise|speex|lame|vosk)') -and
                    ($file -match '[\\/](lwjgl|natives|bin|\.minecraft|jna|versions|libraries|voicechat|opus)[^\\/]*[\\/]' -or $file -match 'lwjgl|jna|voicechat')
    if ($isGameNative) { return $null }
    if ((Test-UnderPath $file $sysRoots) -and -not $inDrop) { return $null }

    $signer = Get-Signer $file
    if ($inDrop) {
        $where = if ($signer) { "signed by $signer" } else { 'not signed' }
        return [PSCustomObject]@{ Level = 'Red'; Text = "loaded from a user folder ($where)" }
    }
    if (-not $signer) { return [PSCustomObject]@{ Level = 'Red'; Text = 'not signed by any publisher' } }
    return [PSCustomObject]@{ Level = 'Yellow'; Text = "signed by $signer (overlays like Discord, OBS, NVIDIA or Steam look like this)" }
}

function Get-LaunchFlags([string]$cmd) {
    $flags = @()
    foreach ($m in [regex]::Matches($cmd, '-javaagent:(?:"([^"]+)"|(\S+))')) {
        $v = if ($m.Groups[1].Success) { $m.Groups[1].Value } else { $m.Groups[2].Value }
        $flags += "Java agent at launch: $v   (normal for Lunar's own agents, suspicious otherwise)"
    }
    foreach ($m in [regex]::Matches($cmd, '-(agentpath|agentlib):(?:"([^"]+)"|(\S+))')) {
        $v = if ($m.Groups[2].Success) { $m.Groups[2].Value } else { $m.Groups[3].Value }
        $flags += "Native agent at launch (-$($m.Groups[1].Value)): $v"
    }
    foreach ($m in [regex]::Matches($cmd, '-D(fabric\.addMods|fabric\.loadMods|fml\.coreMods\.load|fml\.modFolders|forge\.mods|fml\.mods)=(?:"([^"]+)"|(\S+))')) {
        $v = if ($m.Groups[2].Success) { $m.Groups[2].Value } else { $m.Groups[3].Value }
        $flags += "Extra mods loaded from outside the mods folder (-D$($m.Groups[1].Value)): $v"
    }
    if ($cmd -match '-Xrunjdwp|-Xdebug') { $flags += 'Started with the Java debugger turned on (-Xdebug / -Xrunjdwp)' }
    if ($cmd -match '(^|\s)(-noverify|-Xverify:none)(\s|$)') { $flags += 'Bytecode verifier turned off (-noverify) - often needed by injected clients' }
    return $flags
}

function Get-GameDirFromCommand([string]$cmd) {
    if ($cmd -match '--gameDir\s+(?:"([^"]+)"|(\S+))') { if ($Matches[1]) { return $Matches[1] } else { return $Matches[2] } }
    return $null
}

function Get-ModJarSet([string]$gameDir) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    if ($gameDir) {
        $mods = Join-Path $gameDir 'mods'
        if (Test-Path -LiteralPath $mods) {
            Get-ChildItem -LiteralPath $mods -Filter *.jar -File -Recurse -Depth 1 -ErrorAction SilentlyContinue | ForEach-Object { [void]$set.Add($_.FullName) }
        }
    }
    return ,$set
}

function Write-Alert([string]$level, [string]$text, $alerts) {
    $color = switch ($level) { 'Red' { 'Red' } 'Yellow' { 'Yellow' } default { 'Gray' } }
    $stamp = (Get-Date).ToString('HH:mm:ss')
    Write-Host "`r$(' ' * 100)`r" -NoNewline
    Write-Host "  [$stamp] " -ForegroundColor DarkGray -NoNewline
    Write-Host $text -ForegroundColor $color
    if ($null -ne $alerts) { $alerts.Add([PSCustomObject]@{ Time = $stamp; Level = $level; Text = $text }) }
}

function Start-InjectionChecker {
    Write-Section 'Injection Checker'
    $procs = @(Get-MinecraftProcesses)
    while ($procs.Count -eq 0) {
        $in = Read-Host 'No running Minecraft found. Start the game, then press Enter to look again (Q to quit)'
        if ($in -match '^\s*[qQ]') { return }
        $procs = @(Get-MinecraftProcesses)
    }
    $p = $procs[0]
    if ($procs.Count -gt 1) {
        for ($n = 0; $n -lt $procs.Count; $n++) {
            $gd = Get-GameDirFromCommand $procs[$n].CommandLine
            Write-Host ('  {0}) PID {1}  {2}' -f ($n + 1), $procs[$n].Pid, $(if ($gd) { Get-ShortPath $gd } else { '' }))
        }
        $pick = 0
        while ($pick -lt 1 -or $pick -gt $procs.Count) { [void][int]::TryParse((Read-Host "Which Minecraft? 1-$($procs.Count)"), [ref]$pick) }
        $p = $procs[$pick - 1]
    }

    $gameDir = Get-GameDirFromCommand $p.CommandLine
    $javaHome = if ($p.Path) { Split-Path -Parent (Split-Path -Parent $p.Path) } else { $null }
    $up = ''
    if ($p.Started) { $t = (Get-Date) - $p.Started; $up = ' - up {0}h {1}m {2}s' -f [int][math]::Floor($t.TotalHours), $t.Minutes, $t.Seconds }
    Write-Host "> Minecraft PID $($p.Pid)$up" -ForegroundColor White
    if ($gameDir) { Write-Host "  game folder: $(Get-ShortPath $gameDir)" -ForegroundColor Gray }
    if ($p.Path)  { Write-Host "  java: $(Get-ShortPath $p.Path)" -ForegroundColor Gray }
    Write-Host

    $alerts = New-Object 'System.Collections.Generic.List[object]'

    # 1. launch options
    Write-Section 'Launch options'
    $flags = @(Get-LaunchFlags $p.CommandLine)
    if ($flags.Count -eq 0) { Write-Host '  Nothing unusual.' -ForegroundColor Green }
    foreach ($f in $flags) { Write-Alert 'Red' $f $alerts }
    Write-Host

    # 2. DLLs loaded right now
    Write-Section 'Loaded DLLs'
    $baseline = Get-ProcessModuleFiles $p.Pid
    if ($null -eq $baseline) {
        Write-Host '  Could not read the loaded DLLs (the game may be running as administrator - run this as administrator too).' -ForegroundColor Red
        $baseline = @()
    } else {
        Write-Host "  $($baseline.Count) loaded. Checking..." -ForegroundColor DarkGray
        $odd = 0
        foreach ($m in $baseline) {
            $v = Get-ModuleVerdict $m $javaHome
            if ($v) { $odd++; Write-Alert $v.Level "$(Get-ShortPath $m) - $($v.Text)" $alerts }
        }
        if ($odd -eq 0) { Write-Host '  All are normal Windows, Java or game files.' -ForegroundColor Green }
    }
    Write-Host

    # 3. live watch
    Write-Section 'Live watch'
    Write-Host '  Watching for anything injected into the game, mods added or removed, and new programs' -ForegroundColor Gray
    Write-Host '  started from Downloads/Desktop/Temp. Keep this window open during the fight.' -ForegroundColor Gray
    if ($LiveSeconds -le 0) { Write-Host '  Press Q to stop.' -ForegroundColor Yellow }
    Write-Host

    $known = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($m in $baseline) { [void]$known.Add($m) }
    $jarsNow = Get-ModJarSet $gameDir
    $seenProcs = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($x in (Get-Process -ErrorAction SilentlyContinue)) { [void]$seenProcs.Add($x.Id) }
    $drop = Get-UserDropFolders
    $start = Get-Date
    $canReadKeys = $true
    $liveAlerts = 0

    while ($true) {
        $elapsed = (Get-Date) - $start
        if ($LiveSeconds -gt 0 -and $elapsed.TotalSeconds -ge $LiveSeconds) { break }
        if ($canReadKeys) {
            try {
                if ([Console]::KeyAvailable) {
                    $k = [Console]::ReadKey($true)
                    if ($k.Key -eq 'Q' -or $k.Key -eq 'Escape') { break }
                }
            } catch { $canReadKeys = $false }
        }

        if (-not (Get-Process -Id $p.Pid -ErrorAction SilentlyContinue)) {
            Write-Alert 'Yellow' 'Minecraft was closed.' $alerts; $liveAlerts++
            break
        }

        $now = Get-ProcessModuleFiles $p.Pid
        if ($null -ne $now) {
            $nowSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
            foreach ($m in $now) {
                [void]$nowSet.Add($m)
                if ($known.Add($m)) {
                    $v = Get-ModuleVerdict $m $javaHome
                    if ($v) { Write-Alert $v.Level "NEW DLL injected/loaded: $(Get-ShortPath $m) - $($v.Text)" $alerts; $liveAlerts++ }
                    else { Write-Alert 'Gray' "New DLL loaded: $(Get-ShortPath $m) (normal file)" $null }
                }
            }
            foreach ($m in @($known)) {
                if (-not $nowSet.Contains($m) -and (Get-ModuleVerdict $m $javaHome)) {
                    Write-Alert 'Yellow' "DLL unloaded: $(Get-ShortPath $m) (injectors sometimes unload to hide)" $alerts; $liveAlerts++
                    [void]$known.Remove($m)
                }
            }
        }

        $jarsNew = Get-ModJarSet $gameDir
        foreach ($j in $jarsNew) { if (-not $jarsNow.Contains($j)) { Write-Alert 'Red' "Mod ADDED while the game is running: $(Get-ShortPath $j)" $alerts; $liveAlerts++ } }
        foreach ($j in $jarsNow) { if (-not $jarsNew.Contains($j)) { Write-Alert 'Red' "Mod REMOVED while the game is running: $(Get-ShortPath $j)" $alerts; $liveAlerts++ } }
        $jarsNow = $jarsNew

        foreach ($x in (Get-Process -ErrorAction SilentlyContinue)) {
            if ($seenProcs.Add($x.Id)) {
                $xp = $null
                try { $xp = $x.Path } catch {}
                if ($xp -and (Test-UnderPath $xp $drop)) {
                    Write-Alert 'Red' "Program started from a user folder: $(Get-ShortPath $xp) (PID $($x.Id))" $alerts; $liveAlerts++
                }
            }
        }

        $status = '  Watching PID {0} - {1:hh\:mm\:ss} - live alerts: {2}' -f $p.Pid, $elapsed, $liveAlerts
        Write-Host "`r$status   " -ForegroundColor DarkCyan -NoNewline
        Start-Sleep -Milliseconds 1500
    }
    Write-Host "`r$(' ' * 100)`r" -NoNewline
    Write-Host

    Write-Section 'Injection Checker summary'
    $red = @($alerts | Where-Object { $_.Level -eq 'Red' })
    $yellow = @($alerts | Where-Object { $_.Level -eq 'Yellow' })
    if ($alerts.Count -eq 0) {
        Write-Host '  Nothing suspicious found.' -ForegroundColor Green
    } else {
        Write-Host "  Suspicious: $($red.Count)" -ForegroundColor $(if ($red.Count) { 'Red' } else { 'Gray' })
        Write-Host "  Worth a look: $($yellow.Count)" -ForegroundColor $(if ($yellow.Count) { 'Yellow' } else { 'Gray' })
        Write-Host
        foreach ($a in $alerts) {
            Write-Host "  [$($a.Time)] " -ForegroundColor DarkGray -NoNewline
            Write-Host $a.Text -ForegroundColor $(if ($a.Level -eq 'Red') { 'Red' } else { 'Yellow' })
        }
    }
    Write-Host
    Write-Host 'This catches common injection (DLLs, Java agents, swapped mods). Cheats loaded straight into memory' -ForegroundColor DarkGray
    Write-Host 'without a file, or kernel-level cheats, can hide from it. Alerts are leads - check them by hand.' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- start menu

if ($Mode -and $Mode -notin @('mods', 'injection')) {
    Write-Host "Unknown -Mode '$Mode'. Use -Mode mods or -Mode injection." -ForegroundColor Red
    $Mode = ''
}

if (-not $Mode) {
    if ($Path) { $Mode = 'mods' }
    else {
        Write-Host 'What do you want to do?' -ForegroundColor White
        Write-Host '  1) ' -ForegroundColor Yellow -NoNewline; Write-Host 'Mod Analyzer       ' -NoNewline; Write-Host '- check the mods in your mods folders  [default]' -ForegroundColor DarkGray
        Write-Host '  2) ' -ForegroundColor Yellow -NoNewline; Write-Host 'Injection Checker  ' -NoNewline; Write-Host '- watch the running game for injected cheats' -ForegroundColor DarkGray
        Write-Host
        while (-not $Mode) {
            $in = (Read-Host 'Choose 1-2').Trim()
            if ($in -eq '' -or $in -eq '1') { $Mode = 'mods' }
            elseif ($in -eq '2') { $Mode = 'injection' }
            else { Write-Host 'Type 1 or 2.' -ForegroundColor Red }
        }
        Write-Host
    }
}

if ($Mode -eq 'injection') {
    Start-InjectionChecker
    Wait-BeforeClose
    exit 0
}

$running = @(Get-RunningGameDirs)
if ($running.Count -gt 0) {
    Write-Section 'Running Minecraft'
    foreach ($r in $running) {
        $up = ''
        if ($r.Started) {
            $t = (Get-Date) - $r.Started
            $up = ' - up {0}h {1}m {2}s' -f [int][math]::Floor($t.TotalHours), $t.Minutes, $t.Seconds
        }
        Write-Host "> PID $($r.Pid)$up" -ForegroundColor White
        Write-Host "  $(Get-ShortPath $r.Dir)" -ForegroundColor Gray
    }
    Write-Host
}

$folders = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)

function Fix-Sep([string]$p) { return $p.Replace('\', [IO.Path]::DirectorySeparatorChar) }

$home_ = $env:USERPROFILE
$Launchers = @(
    @{ Name = 'Minecraft Launcher (.minecraft, Badlion, SKLauncher)'; Roots = @("$env:APPDATA\.minecraft") },
    @{ Name = 'CurseForge';            Roots = @("$home_\curseforge\minecraft\Instances", "$home_\Documents\curseforge\minecraft\Instances", "$home_\Twitch\Minecraft\Instances") },
    @{ Name = 'Modrinth App';          Roots = @("$env:APPDATA\ModrinthApp", "$env:APPDATA\com.modrinth.theseus") },
    @{ Name = 'Prism / MultiMC / PolyMC'; Roots = @("$env:APPDATA\PrismLauncher", "$env:APPDATA\PolyMC", "$env:LOCALAPPDATA\Programs\PrismLauncher", "$env:LOCALAPPDATA\Programs\MultiMC") },
    @{ Name = 'ATLauncher';            Roots = @("$env:APPDATA\ATLauncher") },
    @{ Name = 'GDLauncher';            Roots = @("$env:APPDATA\gdlauncher_next", "$env:APPDATA\gdlauncher_carbon") },
    @{ Name = 'Lunar Client';          Roots = @("$home_\.lunarclient") },
    @{ Name = 'Dawn Client (formerly Feather)'; Roots = @("$env:APPDATA\.dawn", "$home_\.dawn", "$env:APPDATA\.feather") },
    @{ Name = 'Fast Client';           Roots = @("$env:APPDATA\.fastclient", "$home_\.fastclient", "$env:APPDATA\FastClient", "$env:LOCALAPPDATA\FastClient", "$env:LOCALAPPDATA\Programs\FastClient") },
    @{ Name = 'TLauncher';             Roots = @("$env:APPDATA\.tlauncher", "$env:APPDATA\.minecraft") },
    @{ Name = 'Technic';               Roots = @("$env:APPDATA\.technic") }
)

# Add the mods folders found under each root
function Add-FromRoots($rootList) {
    foreach ($root in $rootList) {
        if (-not $root) { continue }
        $root = Fix-Sep $root
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        $leaf = [IO.Path]::GetFileName($root.TrimEnd('\', '/'))
        if ($ModFolderNames -contains $leaf.ToLowerInvariant()) { [void]$folders.Add($root); continue }
        foreach ($f in (Find-ModFolders $root)) { [void]$folders.Add($f) }
    }
}

# A folder the user typed: use it if it holds jars, otherwise look for mods folders inside it
function Add-UserFolder([string]$p) {
    $p = $p.Trim().Trim('"').Trim("'")
    if ($p -and -not (Test-Path -LiteralPath $p -PathType Container) -and (Test-Path -LiteralPath (Fix-Sep $p) -PathType Container)) { $p = Fix-Sep $p }
    if (-not $p -or -not (Test-Path -LiteralPath $p -PathType Container)) { return $false }
    $p = (Resolve-Path -LiteralPath $p).ProviderPath
    $before = $folders.Count
    if (Get-ChildItem -LiteralPath $p -Filter *.jar -File -Recurse -Depth 1 -ErrorAction SilentlyContinue | Select-Object -First 1) {
        [void]$folders.Add($p)
    }
    foreach ($f in (Find-ModFolders $p)) { [void]$folders.Add($f) }
    if ($folders.Count -eq $before) { [void]$folders.Add($p) }
    return $true
}

if ($Path) {
    foreach ($p in $Path) {
        if (-not (Add-UserFolder $p)) { Write-Host "Not a folder: $p" -ForegroundColor Red }
    }
} else {
    $otherNum = $Launchers.Count + 2
    Write-Host 'Which launcher do you use?' -ForegroundColor White
    Write-Host '  1) ' -ForegroundColor Yellow -NoNewline; Write-Host 'Scan everything (auto-detect)  ' -NoNewline; Write-Host '[default]' -ForegroundColor DarkGray
    for ($n = 0; $n -lt $Launchers.Count; $n++) {
        Write-Host ('{0,3}) ' -f ($n + 2)) -ForegroundColor Yellow -NoNewline; Write-Host $Launchers[$n].Name
    }
    Write-Host ('{0,3}) ' -f $otherNum) -ForegroundColor Yellow -NoNewline; Write-Host 'Other (type the path to your mods folder)'
    Write-Host

    $choice = $null
    while ($null -eq $choice) {
        $in = (Read-Host "Choose 1-$otherNum").Trim()
        if ($in -eq '') { $in = '1' }
        $num = 0
        if ([int]::TryParse($in, [ref]$num) -and $num -ge 1 -and $num -le $otherNum) { $choice = $num }
        else { Write-Host "Type a number from 1 to $otherNum." -ForegroundColor Red }
    }
    Write-Host

    if ($choice -eq $otherNum) {
        while ($true) {
            $typed = Read-Host 'Path to the mods folder'
            if (Add-UserFolder $typed) { break }
            Write-Host 'That folder does not exist. Try again (you can paste it from File Explorer).' -ForegroundColor Red
        }
    } elseif ($choice -ge 2) {
        $pick = $Launchers[$choice - 2]
        Write-Host "[*] Looking for $($pick.Name) mods folders..." -ForegroundColor Yellow -NoNewline
        Add-FromRoots $pick.Roots
        if ($folders.Count -eq 0) {
            Write-Host "`r$(' ' * 70)`r" -NoNewline
            Write-Host "No $($pick.Name) mods folder found in the usual place." -ForegroundColor Red
            while ($true) {
                $typed = Read-Host 'Paste the path to your mods folder (or press Enter to scan everything)'
                if (-not $typed.Trim()) { $choice = 1; break }
                if (Add-UserFolder $typed) { break }
                Write-Host 'That folder does not exist. Try again.' -ForegroundColor Red
            }
        }
    }

    if ($choice -eq 1) {
        Write-Host '[*] Looking for mods folders...' -ForegroundColor Yellow -NoNewline
        $all = @()
        foreach ($l in $Launchers) { $all += $l.Roots }

        # Microsoft Store launcher
        Get-ChildItem -Path (Fix-Sep "$env:LOCALAPPDATA\Packages") -Directory -Filter 'Microsoft.4297127D64EC6_*' -ErrorAction SilentlyContinue |
            ForEach-Object { $all += (Join-Path $_.FullName 'LocalCache\Roaming\.minecraft') }

        # Any dot-folder in AppData\Roaming or the user folder (.dawn, .badlion, other clients)
        foreach ($base in @($env:APPDATA, $home_)) {
            if (-not $base) { continue }
            Get-ChildItem -LiteralPath $base -Directory -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name.StartsWith('.') -and ($SkipDirs -notcontains $_.Name.ToLowerInvariant()) } |
                ForEach-Object { $all += $_.FullName }
        }

        # Running game directories
        foreach ($r in $running) { $all += $r.Dir }

        $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        Add-FromRoots (@($all | Where-Object { $_ -and $seen.Add((Fix-Sep $_)) }))
    }
}

# Collect jars (also one level of subfolders, e.g. mods\1.21\)
$jars = @()
foreach ($f in $folders) {
    $files = @(Get-ChildItem -LiteralPath $f -Filter *.jar -File -Recurse -Depth 1 -ErrorAction SilentlyContinue)
    foreach ($j in $files) {
        $jars += [PSCustomObject]@{ Folder = $f; File = $j }
    }
}

Write-Host "`r$(' ' * 60)`r" -NoNewline
$withJars = @($jars | Select-Object -ExpandProperty Folder -Unique)

if ($withJars.Count -eq 0) {
    Write-Host 'No mods found.' -ForegroundColor Red
    Write-Host 'Run it again and choose "Other" to type the path to your mods folder.' -ForegroundColor DarkGray
    Wait-BeforeClose
    exit 1
}

Write-Section "Mod folders found ($($withJars.Count))"
foreach ($f in $withJars) {
    $count = @($jars | Where-Object { $_.Folder -eq $f }).Count
    Write-Host ("> {0,-11}" -f (Get-LauncherName $f)) -ForegroundColor White -NoNewline
    Write-Host ("{0,4} jars  " -f $count) -ForegroundColor DarkGray -NoNewline
    Write-Host (Get-ShortPath $f) -ForegroundColor Gray
}
Write-Host

# ---------------------------------------------------------------- 2. hash

$i = 0
foreach ($j in $jars) {
    $i++
    Write-Host "`r[*] Hashing $i / $($jars.Count)" -ForegroundColor Yellow -NoNewline
    try { $h = (Get-FileHash -LiteralPath $j.File.FullName -Algorithm SHA1).Hash.ToLowerInvariant() } catch { $h = $null }
    $j | Add-Member -NotePropertyName Hash -NotePropertyValue $h
}
$hashes = @($jars | Where-Object { $_.Hash } | Select-Object -ExpandProperty Hash -Unique)

# ---------------------------------------------------------------- 3. look up

$known = @{}   # hash -> mod name

if (-not $Offline -and $hashes.Count -gt 0) {
    Write-Host "`r[*] Checking $($hashes.Count) unique mods on Modrinth...      " -ForegroundColor Yellow -NoNewline
    $projectIdByHash = @{}
    try {
        for ($n = 0; $n -lt $hashes.Count; $n += 500) {
            $chunk = @($hashes[$n..([math]::Min($n + 499, $hashes.Count - 1))])
            $body  = @{ hashes = $chunk; algorithm = 'sha1' } | ConvertTo-Json -Compress
            $resp  = Invoke-Json 'https://api.modrinth.com/v2/version_files' 'Post' $body
            foreach ($prop in $resp.PSObject.Properties) {
                if ($prop.Value.project_id) { $projectIdByHash[$prop.Name.ToLowerInvariant()] = $prop.Value.project_id }
            }
        }
        $ids = @($projectIdByHash.Values | Select-Object -Unique)
        $titleById = @{}
        for ($n = 0; $n -lt $ids.Count; $n += 100) {
            $chunk = @($ids[$n..([math]::Min($n + 99, $ids.Count - 1))])
            $q = [uri]::EscapeDataString((ConvertTo-Json -InputObject $chunk -Compress))
            foreach ($proj in (Invoke-Json "https://api.modrinth.com/v2/projects?ids=$q")) { $titleById[$proj.id] = $proj.title }
        }
        foreach ($h in $projectIdByHash.Keys) {
            $t = $titleById[$projectIdByHash[$h]]
            if (-not $t) { $t = '(Modrinth project)' }
            $known[$h] = "$t"
        }
    } catch {
        Write-Host "`r[!] Modrinth lookup failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    $left = @($hashes | Where-Object { -not $known.ContainsKey($_) })
    $i = 0
    foreach ($h in $left) {
        $i++
        Write-Host "`r[*] Checking Megabase $i / $($left.Count)              " -ForegroundColor Yellow -NoNewline
        try {
            $r = Invoke-Json "https://megabase.vercel.app/api/query?hash=$h"
            if (-not $r.error -and $r.data -and $r.data.name) { $known[$h] = "$($r.data.name)" }
        } catch {}
    }
}

if (-not $Offline) {
    try {
        $list = Invoke-RestMethod -Uri $TrustedListUrl -UseBasicParsing -UserAgent $UserAgent -TimeoutSec 15
        foreach ($line in ([string]$list -split "`n")) {
            if ($line -match '^\s*([0-9a-fA-F]{40})\s+(.+?)\s*$') { $TrustedMods[$Matches[1].ToLowerInvariant()] = $Matches[2] }
        }
    } catch {}
}
foreach ($h in $hashes) {
    if ($TrustedMods.ContainsKey($h)) { $known[$h] = "$($TrustedMods[$h]) (trusted)" }
}

# CurseForge by fingerprint
if (-not $CurseForgeApiKey -and $env:MODANZER_CF_KEY) { $CurseForgeApiKey = $env:MODANZER_CF_KEY }
if (-not $Offline -and ($CurseForgeApiKey -or $CurseForgeProxyUrl)) {
    $left = @($hashes | Where-Object { -not $known.ContainsKey($_) })
    if ($left.Count -gt 0) {
        $hashByFp = @{}
        $i = 0
        foreach ($h in $left) {
            $i++
            Write-Host "`r[*] CurseForge fingerprint $i / $($left.Count)              " -ForegroundColor Yellow -NoNewline
            $file = ($jars | Where-Object { $_.Hash -eq $h } | Select-Object -First 1).File
            try { $hashByFp[[string](Get-CurseForgeFingerprint $file.FullName)] = $h } catch {}
        }
        Write-Host "`r[*] Checking $($hashByFp.Count) mods on CurseForge...            " -ForegroundColor Yellow -NoNewline
        $fpList = @($hashByFp.Keys | ForEach-Object { [uint32]$_ })
        try {
            $nameByFp = @{}
            if ($CurseForgeApiKey) {
                $cfHeaders = @{ 'x-api-key' = $CurseForgeApiKey; 'Accept' = 'application/json' }
                $body = @{ fingerprints = $fpList } | ConvertTo-Json -Compress
                $r = Invoke-RestMethod -Uri 'https://api.curseforge.com/v1/fingerprints/432' -Method Post -Body $body -ContentType 'application/json' -Headers $cfHeaders -UseBasicParsing -UserAgent $UserAgent -TimeoutSec 20
                $modIdByFp = @{}
                foreach ($m in @($r.data.exactMatches)) { $modIdByFp[[string]$m.file.fileFingerprint] = $m.file.modId }
                if ($modIdByFp.Count -gt 0) {
                    $body = @{ modIds = @($modIdByFp.Values | Select-Object -Unique) } | ConvertTo-Json -Compress
                    $mods = Invoke-RestMethod -Uri 'https://api.curseforge.com/v1/mods' -Method Post -Body $body -ContentType 'application/json' -Headers $cfHeaders -UseBasicParsing -UserAgent $UserAgent -TimeoutSec 20
                    $nameById = @{}
                    foreach ($mod in @($mods.data)) { $nameById[[string]$mod.id] = $mod.name }
                    foreach ($fp in $modIdByFp.Keys) { $nameByFp[$fp] = $nameById[[string]$modIdByFp[$fp]] }
                }
            } else {
                for ($n = 0; $n -lt $fpList.Count; $n += 1000) {
                    $chunk = @($fpList[$n..([math]::Min($n + 999, $fpList.Count - 1))])
                    $body = @{ fingerprints = $chunk } | ConvertTo-Json -Compress
                    $r = Invoke-RestMethod -Uri $CurseForgeProxyUrl -Method Post -Body $body -ContentType 'application/json' -UseBasicParsing -UserAgent $UserAgent -TimeoutSec 30
                    foreach ($prop in $r.matches.PSObject.Properties) { $nameByFp[$prop.Name] = $prop.Value.name }
                }
            }
            foreach ($fp in $nameByFp.Keys) {
                if ($hashByFp.ContainsKey($fp)) {
                    $n = $nameByFp[$fp]
                    if (-not $n) { $n = '(CurseForge project)' }
                    $known[$hashByFp[$fp]] = "$n [CurseForge]"
                }
            }
        } catch {
            $msg = $_.Exception.Message
            try { if ($_.ErrorDetails.Message) { $msg = ($_.ErrorDetails.Message | ConvertFrom-Json).error } } catch {}
            Write-Host "`r[!] CurseForge lookup failed: $msg" -ForegroundColor Red
        }
    }
}

# ---------------------------------------------------------------- 4. scan unknown jars

$scanned = @{}   # hash -> list of hits
$unknownHashes = @($hashes | Where-Object { -not $known.ContainsKey($_) })
$i = 0
foreach ($h in $unknownHashes) {
    $i++
    Write-Host "`r[*] Scanning unknown mods for cheat strings $i / $($unknownHashes.Count)      " -ForegroundColor Yellow -NoNewline
    $jar  = ($jars | Where-Object { $_.Hash -eq $h } | Select-Object -First 1).File
    $hits = New-Object 'System.Collections.Generic.List[object]'
    try {
        $fs = [IO.File]::OpenRead($jar.FullName)
        try { Search-Jar $fs $jar.Name $hits } finally { $fs.Dispose() }
    } catch {
        $hits.Add([PSCustomObject]@{ Where = $jar.Name; Strings = @("could not open: $($_.Exception.Message)") })
    }
    $scanned[$h] = $hits
}

Write-Host "`r$(' ' * 80)`r" -NoNewline

# ---------------------------------------------------------------- 5. report

$totalCheat = 0; $totalUnknown = 0; $totalVerified = 0; $totalCf = 0
$cheatList = New-Object 'System.Collections.Generic.List[object]'

# "Launcher > instance" for a mods folder, e.g. "Modrinth > Atzlk_s PMW"
function Get-InstanceLabel([string]$modsFolder) {
    $launcher = Get-LauncherName $modsFolder
    $parent = Split-Path -Parent $modsFolder
    $inst = Split-Path -Leaf $parent
    if ($inst -in @('.minecraft', 'minecraft', '')) {
        $up = Split-Path -Leaf (Split-Path -Parent $parent)
        if ($up -and $up -notin @('Roaming', 'AppData') -and -not $up.StartsWith('.')) { $inst = $up } else { $inst = $null }
    }
    if ($inst -and $inst.StartsWith('.')) { $inst = $null }
    if ($inst -and $inst -ne $launcher -and ".$inst" -ne $launcher -and $inst -ne ".$launcher".TrimStart('.')) { return "$launcher > $inst" }
    return $launcher
}

foreach ($f in $withJars) {
    Write-Section "$(Get-LauncherName $f)  $(Get-ShortPath $f)"
    $cfRecords = Get-CurseForgeRecords $f
    $here = @($jars | Where-Object { $_.Folder -eq $f } | Sort-Object { $_.File.Name })

    foreach ($j in $here) {
        $name = $j.File.Name
        if ($j.Hash -and $known.ContainsKey($j.Hash)) {
            $totalVerified++
            Write-Host ('  [OK]    {0,-32}' -f $known[$j.Hash]) -ForegroundColor Green -NoNewline
            Write-Host $name -ForegroundColor DarkGray
            continue
        }
        $hits = $null
        if ($j.Hash) { $hits = $scanned[$j.Hash] }
        if ($hits -and $hits.Count -gt 0) {
            $totalCheat++
            Write-Host "  [CHEAT] $name" -ForegroundColor Red
            foreach ($hit in $hits) {
                Write-Host "          $($hit.Where): " -ForegroundColor Gray -NoNewline
                Write-Host ($hit.Strings -join ', ') -ForegroundColor Magenta
            }
            Write-Host "          sha1: $($j.Hash)" -ForegroundColor DarkGray
            $allStrings = @($hits | ForEach-Object { $_.Strings } | Select-Object -Unique)
            $cheatList.Add([PSCustomObject]@{ Name = $name; Where = (Get-InstanceLabel $f); Strings = ($allStrings -join ', ') })
        } elseif ($cfRecords.ContainsKey($name) -and (-not $cfRecords[$name].Length -or [int64]$cfRecords[$name].Length -eq $j.File.Length)) {
            # Installed by the CurseForge app and no cheat names inside.
            $totalCf++
            Write-Host ('  [CF]    {0,-32}' -f $cfRecords[$name].Name) -ForegroundColor Cyan -NoNewline
            Write-Host $name -ForegroundColor DarkGray
        } else {
            $totalUnknown++
            Write-Host "  [?]     $name" -ForegroundColor DarkYellow
            $zone = Get-ZoneIdentifier $j.File.FullName
            if ($zone) { Write-Host "          downloaded from $zone" -ForegroundColor DarkGray }
        }
    }
    Write-Host
}

Write-Section 'Summary'
Write-Host "  Verified: $totalVerified" -ForegroundColor Green
if ($totalCf -gt 0) { Write-Host "  CF app:   $totalCf  (installed by the CurseForge app, no cheat names found)" -ForegroundColor Cyan }
Write-Host "  Unknown:  $totalUnknown" -ForegroundColor DarkYellow
Write-Host "  Cheat:    $totalCheat"   -ForegroundColor $(if ($totalCheat -gt 0) { 'Red' } else { 'Gray' })
if ($Offline) { Write-Host '  (offline mode - no online lookups were made)' -ForegroundColor DarkGray }

if ($cheatList.Count -gt 0) {
    Write-Host
    Write-Section "Cheats found ($($cheatList.Count))"
    foreach ($group in ($cheatList | Group-Object Where)) {
        Write-Host "  $($group.Name)" -ForegroundColor White
        foreach ($c in $group.Group) {
            Write-Host "    [CHEAT] $($c.Name)  " -ForegroundColor Red -NoNewline
            Write-Host $c.Strings -ForegroundColor Magenta
        }
    }
}
Write-Host
Write-Host 'Unknown does not mean cheat - it just is not on Modrinth/Megabase/CurseForge. Cheat hits are name matches; check them by hand.' -ForegroundColor DarkGray
Wait-BeforeClose
