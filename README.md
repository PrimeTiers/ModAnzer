# ModAnzer

A Minecraft mod analyzer for Windows, written in Java. It finds every mods folder on the PC by itself, so there is no path to paste, then checks each mod against Modrinth and Megabase and scans unknown mods for cheat module names.

Started from [HabibiModAnalyzer](https://github.com/HadronCollision/PowershellScripts) by HadronCollision.

## Website

**https://modanzer.onrender.com** has the download buttons and what the tools do and don't do. It's a Render static site built from this repo (`site/index.html`), and it updates on every push to `main`.

## Run it

**Easiest:** download **[ModAnzer.exe](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.exe)** and open it. It is a small Windows app in PrimeTiers colours: pick a check, press **Start scan**, then press **Get code** and give the code to staff. Java is built in, so there is nothing else to install. If Windows SmartScreen appears, click **More info → Run anyway**.

If your antivirus blocks `ModAnzer.exe` (it unpacks itself and starts), use **[ModAnzer-Windows.zip](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer-Windows.zip)** instead: unzip it and open `ModAnzer\ModAnzer.exe`.

**With Java already installed (22 or newer):** download **[ModAnzer.jar](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.jar)** and double-click it, or run:

```
java --enable-native-access=ALL-UNNAMED -jar ModAnzer.jar
```

**[ModAnzer.bat](https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.bat)** does the same thing: it runs the `ModAnzer.jar` next to it, or downloads it first.

The source is in [`windows/`](windows). It is plain Java with no libraries; build it with `mvn -f windows/pom.xml package`.

Run it with `--cli` to use it in a console instead of the window.

## What it can do

Pick one of these in the window, then press **Start scan**:

```
  Mod Analyzer       - check the mods in your mods folders
  Injection Checker  - check the running game, its memory, and watch it live
  System Check       - traces: programs that ran, deleted files, cleared logs, USB
  Full Screenshare   - all three, one after another
```

For the Injection Checker and System Check, **Restart as admin** (top right) lets it read more. The player can say no; it then runs the checks a normal user can see and says which ones it skipped.

### Injection Checker

Run it while the player is in Minecraft (Windows only):

1. **Launch options:** flags anything that adds code at startup, such as `-javaagent`, `-agentpath`, `fabric.addMods`/`loadMods`, the Java debugger, or `-noverify`. Lunar uses its own Java agents, so check where an agent's file lives before calling it a cheat.
2. **Loaded DLLs:** flags Java agent support (`instrument.dll`), the debugger (`jdwp.dll`), DLLs loaded from Downloads/Desktop/Temp, and unsigned DLLs. Signed overlays (Discord, OBS, NVIDIA, Steam) show as "worth a look".
3. **Game memory (read-only):** reads Minecraft's memory and flags cheat module and client names (KillAura, AutoCrystal, Meteor, Wurst, LiquidBounce...), plus **hidden programs**: a complete DLL/EXE sitting in the game's private memory with no file behind it, which is how "manual-mapped" cheats hide from file scans. A cheat name can also be in memory because someone typed it in chat, so check the context.
4. **Live watch:** alerts the moment a DLL is injected or a flagged one unloads, a mod is added or removed, or a program starts from Downloads/Desktop/Temp. Press **Q** to stop and see the summary.

### System Check

Looks for traces of cheating on the PC (Windows only):

| Check | What it looks for | Needs admin |
|---|---|---|
| PC and running programs | recent restart, virtual machine, cheat tools running now (Cheat Engine, Process Hacker, injectors, macro tools...) | no |
| Logging services | EventLog, SysMain (Prefetch), PcaSvc, DiagTrack or BAM turned off | no |
| Programs that ran (BAM) | last 24 h: cheat tools, programs that ran and were then **deleted**, unsigned programs from Downloads/Desktop/Temp | **yes** |
| Prefetch | cheat tools that ran in the last 24 h, an emptied or freshly cleared Prefetch folder | **yes** |
| PowerShell history | clearing history or event logs, deleting mods/`.jar`/Prefetch, antivirus exclusions, stopping logging services, downloading `.jar`/`.dll`/`.exe` | no |
| Event logs | logs cleared in the last 7 days (System 104, Security 1102), file change journal deleted (3079) | Security log only |
| USB drives | drives plugged in now or in the last 24 h | no |
| Deleted and downloaded files | `.jar`/`.dll`/`.exe`/`.ahk` in the Recycle Bin, recent mods and tools in Downloads/Desktop | no |
| Minecraft accounts | account **names** from launcher files and game logs, to spot alts. Never tokens or emails | no |

Everything is a lead, not proof. Check each one with the player.

### Sending results to staff

When the scan ends, press **Get code**. Sending saves the results on ModAnzer's server and shows a 6-character code, like `7k3qxf`. The player gives the code to staff, and staff run `/ssresult refcode:7k3qxf user:@player` in the PrimeTiers bot to see the report. Closing the window instead sends nothing. In `--cli` mode the code is sent at the end unless you pass `--no-send`.

- Reports go to ModAnzer's server ([`supabase/functions/modanzer-report`](supabase/functions/modanzer-report/index.ts)), which saves them in the PrimeTiers bot's MongoDB, in the `modanzer_reports` collection, under a random reference code. Nothing is posted until staff look the code up with `/ssresult`.
- The server keeps only the fields ModAnzer sends, and caps their sizes. It rate-limits each sender to 6 reports a minute and doesn't store IP addresses.
- Windows user folders are shown as `~` and user names as `<user>`. Minecraft account **names** are included, never tokens or emails.

Setup, in the **excusemecounter** Supabase project under **Edge Functions → Secrets**:

| Secret | Value |
|---|---|
| `MODANZER_MONGODB_URI` | connection string for the PrimeTiers MongoDB. Use a separate database user that can only insert into `modanzer_reports`, not the bot's own login |
| `MODANZER_MONGODB_DB` | database name, if it isn't `primetiers` |

Saved report document:

```json
{
  "tool": "ModAnzer", "mode": "Full Screenshare", "finished": "2026-11-21 09:30:00 +07:00",
  "refCode": "7k3qxf", "accounts": ["Steve"],
  "mods": { "Folders": [], "Verified": 0, "CurseForgeApp": 0, "Unknown": 0, "Cheat": 0, "Cheats": [{ "Name": "", "Where": "", "Strings": "" }], "Unknowns": [{ "Name": "", "Where": "" }] },
  "system":    { "Admin": true, "Alerts": [{ "Time": "09:20", "Level": "Red", "Text": "" }] },
  "injection": { "Admin": true, "Uptime": "", "GameFolder": "", "Alerts": [] },
  "status": "new", "receivedAt": "<date>", "senderHash": "<16 hex>"
}
```

### Antivirus

The Mod Analyzer and System Check only read files, logs and the registry. The **memory scan** is different: to read Minecraft's memory, ModAnzer calls the Windows functions for reading another program's memory (see [`Native.java`](windows/src/main/java/com/napatc1/modanzer/Native.java)). That's what cheats and malware use too, so **Windows Defender may flag or block the app**. It only opens Minecraft for reading, and it never writes to, suspends or injects into any process. If the memory reader is blocked, ModAnzer skips the memory scan and carries on.

When it starts, it first shows what it does and doesn't do. The Mod Analyzer then scans every launcher it finds, or only the one you pick in the **Launcher** list (Minecraft Launcher, CurseForge, Modrinth App, Prism / MultiMC, Lunar Client, or any folder you choose with **Other folder...**). If the launcher you pick has no mods folder in its usual place, it scans everything.

### Command line

Add `--cli` to run in a console. Options:

| Option | What it does |
|---|---|
| `--mode mods` / `injection` / `system` / `full` | Which check to run (default `mods`) |
| `--path "D:\pack\mods"` | Scan only this folder (can be given several times) |
| `--launcher CurseForge` | Scan only a launcher whose name contains this text |
| `--offline` | Skip the online lookups; only do the cheat-string scan |
| `--live-seconds 300` | Stop the Injection Checker's live watch after 5 minutes (otherwise it runs until you close it) |
| `--no-send` | Don't send the results to staff at the end |
| `--report-out report.json` | Write the report to a file instead of sending it |

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
2. Looks the hashes up on Modrinth in one batched request, then on Megabase and CurseForge for anything Modrinth doesn't know. Only hashes and fingerprints leave your PC, never files.
3. Checks the mods it recognised too, because cheats and PvP macros can be published on Modrinth. A recognised mod counts as **[CHEAT]** when its Modrinth slug or SHA1 is in [`flagged.txt`](flagged.txt), when its name says what it does (AutoTotem, Crystal Macro, TriggerBot, Aim Assist and so on), or when its jar has a class named like a cheat module (`AutoTotem.class`). Add a Modrinth macro to `flagged.txt` on GitHub to catch it without a new release.
4. Opens each unrecognised jar in memory, including jars nested in `META-INF/jars`, and searches it for cheat module names.
   - **Strong names** such as AutoCrystal, TriggerBot and KillAura count wherever they appear.
   - **Generic names** (Velocity, Hitboxes, AimAssist, AutoArmor, FastPlace) are also ordinary game-code words, like `getVelocity` in physics mods, so they only count when they are a whole name on their own: a class called `Velocity.class` or `VelocityModule.class`, or the exact text `Velocity` stored as a separate string. That's how cheat module names look.
5. Prints the results grouped by launcher and instance: **[OK]** verified, **[CF]** installed by the CurseForge app with no cheat names inside, **[?]** unknown (with the download URL Windows recorded, if any), and **[CHEAT]** with the strings that matched and the file's SHA1.
6. Ends with a summary and a **Cheats found** list showing every cheat and which launcher and instance it was in.

It is read-only: nothing is modified, uploaded, extracted to disk, or installed.

"Unknown" doesn't mean cheat. It only means the mod isn't on Modrinth, Megabase or CurseForge. A cheat hit is a name match, so check it by hand before acting on it.

### Trusted mods

Some legit client mods, such as FastClient HUD and Dawn, contain cheat-like names. To mark them OK, add a line to [`trusted.txt`](trusted.txt) in this repo with the SHA1 printed under the `[CHEAT]` result:

```
1807bd0592525fe40d0d0ee69d30b7486c48fdc7  FastClient HUD 1.21.11
```

You can edit the file on GitHub, even from a phone, and the app picks it up on their next run. Each version of a mod has its own SHA1, so add a line whenever a trusted mod updates. Mods are trusted by exact SHA1 rather than by name, so a cheat renamed to look like a trusted mod is still caught.

### CurseForge

Mods are checked against CurseForge's database by fingerprint, like they are on Modrinth and Megabase, and matches show as `[OK] Name [CurseForge]`.

CurseForge requires an API key and asks that it be kept private, so the lookups go through a small server function ([`supabase/functions/modanzer-cf`](supabase/functions/modanzer-cf/index.ts)) that holds the key. The app sends it only CurseForge fingerprints, which are numbers. It can only do fingerprint lookups and rate-limits each IP address. The key is stored as the `CURSEFORGE_API_KEY` secret in the Supabase project, never in this repo or the downloads.

- **[CF] results** come from the CurseForge app's own install record (`minecraftinstance.json`) and still work if the server is down. They're still scanned for cheat names, so a cheat added to that record still shows as `[CHEAT]`.

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

Nothing in ModAnzer is hidden or obfuscated. The full source of both apps is in this repo.

- **Windows:** the memory scan is the part most likely to be flagged (see **Antivirus** above), and so is any program that unpacks itself and starts, which is why a plain `.zip` is offered too. The Mod Analyzer also lists folders in `%APPDATA%` and reads `.jar` files, which a heuristic scanner could mistake for a stealer, but it never opens tokens or passwords and sends only hashes. If Windows Defender flags it, [report a false positive to Microsoft](https://www.microsoft.com/en-us/wdsi/filesubmission).
- **Android:** Play Protect may warn about any app that doesn't come from the Play Store. Registering as a developer (above) removes that warning.

