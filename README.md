# ModAnzer

A Minecraft mod analyzer for Windows. It finds every mods folder on the PC by itself, so there is no path to paste, then checks each mod against Modrinth and Megabase and scans unknown mods for cheat module names.

Based on [HabibiModAnalyzer](https://github.com/HadronCollision/PowershellScripts) by HadronCollision.

## Run it

**Easiest way:** download `ModAnzer.ps1`, right-click it and choose **Run with PowerShell**. If Windows asks whether to run a downloaded script, choose **Run once**. The window stays open at the end until you press Enter.

**From a PowerShell window:**

1. Download **[ModAnzer.ps1](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.ps1)** from the latest release. If you like, check it against `SHA256SUMS.txt` on the [release page](https://github.com/napatc1/ModAnzer/releases/tag/apk).
2. Open PowerShell in the folder you saved it to and run:

```powershell
Unblock-File .\ModAnzer.ps1
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\ModAnzer.ps1
```

`Unblock-File` tells Windows you trust the file you downloaded. `RemoteSigned` applies only to this one run and doesn't change any settings.

Please don't run it with a pasted `irm ... | iex` one-liner. Downloading code and running it straight from memory with the execution policy bypassed is exactly how malware gets in, so antivirus tools watch for that pattern and may block or flag it. Running a saved file avoids that, and you can read the file before running it.

### Desktop shortcut

To start it with a double-click, download **[ModAnzer.ico](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.ico)** and put it next to `ModAnzer.ps1`. Then:

1. Right-click the desktop and choose **New → Shortcut**.
2. For the location, enter the following, changing the path to where you saved the script:
   ```
   powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "C:\Users\you\Downloads\ModAnzer\ModAnzer.ps1"
   ```
3. Name it **ModAnzer** and click **Finish**.
4. Right-click the shortcut and choose **Properties → Change Icon → Browse**, then pick `ModAnzer.ico`.

Make the shortcut on each PC rather than sending a `.lnk` file to someone. A shortcut only points to files on the PC it was made on, so the receiver gets a broken shortcut with no icon. Discord and antivirus tools also often block shortcut files that start PowerShell, because malware spreads that way. To share it, send the [release page](https://github.com/napatc1/ModAnzer/releases/tag/apk) link instead.

## Two tools in one

When it starts, the script asks which tool to use:

```
  1) Mod Analyzer       - check the mods in your mods folders  [default]
  2) Injection Checker  - watch the running game for injected cheats
```

### Injection Checker

Run it while the player is in Minecraft (it works on Windows only):

1. **Launch options:** flags anything that adds code to the game at startup, such as `-javaagent`, `-agentpath`, `fabric.addMods`/`loadMods`, the Java debugger, or `-noverify`. Lunar uses its own Java agents, so check where an agent's file lives before calling it a cheat.
2. **Loaded DLLs:** lists every DLL inside the game and flags the unusual ones: Java agent support (`instrument.dll`) or the debugger (`jdwp.dll`), DLLs loaded from Downloads/Desktop/Temp, and DLLs that aren't signed by any publisher. Signed DLLs from overlays (Discord, OBS, NVIDIA, Steam) show as "worth a look".
3. **Live watch:** keeps running during the fight and alerts the moment a new DLL is injected into the game, a flagged DLL unloads, a mod is added to or removed from the mods folder, or a program starts from Downloads/Desktop/Temp. Press **Q** to stop and see the summary.

It only reads the list of loaded DLLs. It never reads or changes the game's memory. Cheats loaded straight into memory without a file, and kernel-level cheats, can hide from it, so treat its alerts as leads. If the game runs as administrator, run the script as administrator too, or it can't see the DLL list.

Use `-Mode injection` to skip the menu, and `-LiveSeconds 300` to stop the watch on its own after 5 minutes.

When it starts, it first shows what it does and doesn't do, and waits for you to press Enter. Then it asks which launcher you use:

```
  1) Scan everything (auto-detect)  [default]
  2) Minecraft Launcher (.minecraft, Badlion, SKLauncher)
  3) CurseForge
  4) Modrinth App
  5) Prism / MultiMC / PolyMC
  6) ATLauncher
  7) GDLauncher
  8) Lunar Client
  9) Dawn Client (formerly Feather)
 10) Fast Client
 11) TLauncher
 12) Technic
 13) Other (type the path to your mods folder)
```

Press Enter for 1. If the launcher you pick has no mods folder in its usual place, it asks you to paste the path, or press Enter to scan everything.

### Options

| Option | What it does |
|---|---|
| `-Path "D:\pack\mods"` | Skip the menu and scan only these folders (can list several, comma-separated) |
| `-Offline` | Skip the online lookups; only do the cheat-string scan |
| `-SkipIntro` | Don't show the "what this script does" notice at the start |
| `-Mode mods` / `-Mode injection` | Skip the start menu and go straight to that tool |
| `-NoPause` | Don't wait for Enter before closing at the end |

## Where it looks

- Vanilla launcher: `%APPDATA%\.minecraft` (including the Microsoft Store version)
- CurseForge: `~\curseforge\minecraft\Instances\*`
- Modrinth App: `%APPDATA%\ModrinthApp\profiles\*`
- Prism, PolyMC, MultiMC, ATLauncher, GDLauncher, Technic
- Fast Client (`%APPDATA%\.fastclient` and similar)
- Dawn, formerly Feather (`%APPDATA%\.dawn`, and `.feather` with its `user-mods` folder), Lunar, TLauncher
- Any dot-folder in `%APPDATA%` or your user folder, such as `.badlion`
- The game folder of any Minecraft that is running right now (read from `--gameDir`)

Jars in one level of subfolders (for example `mods\1.21\`) are included too.

## What it does

1. Hashes every `.jar` with SHA1.
2. Looks the hashes up on Modrinth in one batched request, then on Megabase for anything Modrinth doesn't know. With a CurseForge API key (see below), it also checks CurseForge by fingerprint. Only hashes leave your PC, never files.
3. Opens each unrecognised jar in memory, including jars nested in `META-INF/jars`, and searches it for cheat module names.
   - **Strong names** such as AutoCrystal, TriggerBot and KillAura count wherever they appear.
   - **Generic names** (Velocity, Hitboxes, AimAssist, AutoArmor, FastPlace) are also ordinary game-code words, like `getVelocity` in physics mods, so they only count when they are a whole name on their own: a class called `Velocity.class` or `VelocityModule.class`, or the exact text `Velocity` stored as a separate string. That's how cheat module names look.
4. Prints the results grouped by launcher and instance: **[OK]** verified, **[CF]** installed by the CurseForge app with no cheat names inside, **[?]** unknown (with the download URL Windows recorded, if any), and **[CHEAT]** with the strings that matched and the file's SHA1.
5. Ends with a summary and a **Cheats found** list showing every cheat and which launcher and instance it was in.

It is read-only: nothing is modified, uploaded, extracted to disk, or installed.

"Unknown" doesn't mean cheat. It only means the mod isn't on Modrinth, Megabase or CurseForge. A cheat hit is a name match, so check it by hand before acting on it.

### Trusted mods

Some legit client mods, such as FastClient HUD and Dawn, contain cheat-like names. To mark them OK, add a line to [`trusted.txt`](trusted.txt) in this repo with the SHA1 printed under the `[CHEAT]` result:

```
1807bd0592525fe40d0d0ee69d30b7486c48fdc7  FastClient HUD 1.21.11
```

You can edit the file on GitHub, even from a phone, and the script and app pick it up on their next run. Each version of a mod has its own SHA1, so add a line whenever a trusted mod updates. Mods are trusted by exact SHA1 rather than by name, so a cheat renamed to look like a trusted mod is still caught.

### CurseForge

- **[CF] results** come from the CurseForge app's own install record (`minecraftinstance.json`) and work without any setup. They're still scanned for cheat names, so a cheat added to that record still shows as `[CHEAT]`.
- **Full CurseForge lookups** need a free API key from [console.curseforge.com](https://console.curseforge.com). Put it in `$CurseForgeApiKey` near the top of `ModAnzer.ps1`, or set the `MODANZER_CF_KEY` environment variable. CurseForge's terms say not to share your key, so keep it out of copies you send to other people if you'd rather not expose it.

## Changes from HabibiModAnalyzer

- Automatic discovery of mods folders across launchers and clients
- One batched Modrinth request instead of two requests per mod
- Each identical jar is checked once, even if it is in several instances
- In-memory scanning with no temp folder, and nested jars inside nested jars
- Case-sensitive matching to cut false positives, with duplicate strings removed and a few added
- Uptime shown for every running instance, along with its game folder
- A launcher menu with an "Other" option for any mods folder

## Android app

There's also an Android version in [`android/`](android/). Download it here, on your phone:

**[ModAnzer.apk](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.apk)**

Open the downloaded file to install it. Android will ask you to allow installs from your browser or file manager the first time.

In the app, tap **Pick folder** and choose your mods folder, or a whole launcher or `.minecraft` folder; every mods folder inside it gets scanned. **Pick .jar files** checks individual jars instead. For PojavLauncher and similar launchers, open the picker's side menu (☰) and choose the launcher there. It does the same checks as the PC version: Modrinth and Megabase lookups by hash, then a cheat-string scan of unknown mods, including nested jars.

The app needs only the Internet permission and reads only the files or folder you pick. It has no ads, trackers or third-party libraries.

### Signing and developer verification

GitHub builds the APK whenever something in `android/` changes and signs it with a private key stored in the repo's Actions secrets (`MODANZER_KEYSTORE_BASE64` and `MODANZER_KEYSTORE_PASSWORD`). The key is not in the repo. If the secrets are missing, the build uses a temporary key and new builds won't install over old ones.

From 30 September 2026, certified Android phones in Brazil, Indonesia, Singapore and Thailand only install apps from developers registered with Google, and other countries follow later. To keep the APK installable there, register the app's package name (`com.napatc1.modanzer`) and its signing key in the Android Developer Console. A free limited-distribution account allows up to 20 devices; a full account has a one-time $25 fee.

## What antivirus might say

Neither version hides or obfuscates anything. The script is plain text, and the app's full source is in this repo.

- **Windows:** The script lists folders in `%APPDATA%` and your user folder and reads `.jar` files, so a heuristic scanner could mistake it for a stealer. It never opens launcher account files or tokens, and it sends only SHA1 hashes. If Windows Defender flags it, [report a false positive to Microsoft](https://www.microsoft.com/en-us/wdsi/filesubmission).
- **Android:** Play Protect may warn about any app that doesn't come from the Play Store. Registering as a developer (above) is what removes that warning.
