<#
.SYNOPSIS
    בונה את ה-APK של אפליקציית Mealie לאנדרואיד, בודק אותו ומעתיק אותו ל-dist\android.

.DESCRIPTION
    הסקריפט מוצא לבד JDK ו-Android SDK, מריץ את אותן בדיקות כמו ה-CI (מחרוזות, lint, בדיקות יחידה),
    בונה את ה-APK, מאמת את החתימה ומעתיק אותו עם שם שכולל את הגרסה.

    חתימת שחרור: אם קיים android\keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
    או שמשתני MEALIE_KEYSTORE_* מוגדרים, ה-release נחתם במפתח השחרור. אחרת הוא נחתם במפתח debug
    ומתאים רק לבדיקה במכשיר שלך.

.EXAMPLE
    .\build-apk.ps1
    בניית release מלאה עם lint ובדיקות.

.EXAMPLE
    .\build-apk.ps1 -BuildType Debug -Install
    בניית debug והתקנה במכשיר המחובר.
#>
[CmdletBinding()]
param(
    [ValidateSet("Release", "Debug", "All")]
    [string]$BuildType = "Release",

    # ניקוי תוצרי בנייה קודמים לפני הבנייה
    [switch]$Clean,

    [switch]$SkipTests,

    [switch]$SkipLint,

    # התקנה במכשיר או אמולטור מחובר דרך adb
    [switch]$Install,

    # עצירה אם ה-release לא נחתם במפתח השחרור
    [switch]$RequireSigning,

    # בנייה בלי רשת, מהמטמון של Gradle בלבד
    [switch]$Offline,

    # פתיחת תיקיית התוצרים בסיום
    [switch]$Open,

    [string]$OutputDir,

    # מספר תיקון האפליקציה (0-99). ברירת המחדל: אחד יותר מהשחרור האחרון ב-GitHub, כדי שההתקנה לא תיחסם כ"גרסה ישנה יותר"
    [ValidateRange(-1, 99)]
    [int]$AppPatch = -1,

    # המאגר שממנו האפליקציה מושכת עדכונים ובו נבדקים השחרורים
    [string]$UpdateRepo = "yosef-chai/mealie"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$RepoRoot = $PSScriptRoot
$AndroidDir = Join-Path $RepoRoot "android"
if (-not $OutputDir) { $OutputDir = Join-Path $RepoRoot "dist\android" }
$Started = Get-Date
$Step = 0

function Write-Step([string]$Text) {
    $script:Step++
    Write-Host ""
    Write-Host ("[{0}] {1}" -f $script:Step, $Text) -ForegroundColor Cyan
}

function Write-Ok([string]$Text) { Write-Host "  ✓ $Text" -ForegroundColor Green }
function Write-Note([string]$Text) { Write-Host "  • $Text" }
function Write-Warn([string]$Text) { Write-Host "  ! $Text" -ForegroundColor Yellow }

function Stop-Build([string]$Text, [string]$Hint) {
    Write-Host ""
    Write-Host "הבנייה נעצרה: $Text" -ForegroundColor Red
    if ($Hint) { Write-Host $Hint -ForegroundColor Yellow }
    exit 1
}

# גרסת ה-JDK נקראת מקובץ release שבתיקייה, בלי להריץ java (ב-PowerShell 5.1 פלט stderr של java נזרק כשגיאה)
function Get-JdkMajor([string]$JdkHome) {
    if (-not $JdkHome) { return 0 }
    $javaExe = Join-Path $JdkHome "bin\java.exe"
    $release = Join-Path $JdkHome "release"
    if (-not (Test-Path $javaExe) -or -not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
    if (-not $line) { return 0 }
    $version = $line.Matches[0].Groups[1].Value
    if ($version -match '^1\.(\d+)') { return [int]$Matches[1] }
    if ($version -match '^(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-Jdk {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA "Programs"))) {
        if (-not $base) { continue }
        $candidates.Add((Join-Path $base "Android\Android Studio\jbr"))
        $candidates.Add((Join-Path $base "Android\Android Studio Preview\jbr"))
    }
    $toolbox = Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps"
    if (Test-Path $toolbox) {
        Get-ChildItem $toolbox -Directory -Filter "*android-studio*" -ErrorAction SilentlyContinue |
            ForEach-Object { Get-ChildItem $_.FullName -Recurse -Directory -Filter "jbr" -Depth 3 -ErrorAction SilentlyContinue } |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
    foreach ($base in @((Join-Path $env:ProgramFiles "Eclipse Adoptium"), (Join-Path $env:ProgramFiles "Java"), (Join-Path $env:ProgramFiles "Microsoft"))) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    foreach ($path in $candidates) {
        $major = Get-JdkMajor $path
        if ($major -ge 17) { return [pscustomobject]@{ Home = $path; Major = $major } }
    }
    return $null
}

function Read-Properties([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or $trimmed.StartsWith("!")) { continue }
        $index = $trimmed.IndexOfAny([char[]]@('=', ':'))
        if ($index -lt 1) { continue }
        $key = $trimmed.Substring(0, $index).Trim()
        # ב-local.properties נתיבים נשמרים עם \\ ו-\: לפי פורמט Java properties
        $value = $trimmed.Substring($index + 1).Trim() -replace '\\(.)', '$1'
        $result[$key] = $value
    }
    return $result
}

function Find-Sdk {
    $localProps = Join-Path $AndroidDir "local.properties"
    $candidates = @()
    if (Test-Path $localProps) {
        $props = Read-Properties $localProps
        if ($props.ContainsKey("sdk.dir")) { $candidates += $props["sdk.dir"] }
    }
    $candidates += @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    foreach ($path in $candidates) {
        if ($path -and (Test-Path (Join-Path $path "platform-tools")) -and (Test-Path (Join-Path $path "build-tools"))) {
            return (Resolve-Path $path).Path
        }
    }
    return $null
}

function Get-LatestBuildTool([string]$Sdk, [string]$Name) {
    $dirs = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($dir in $dirs) {
        foreach ($ext in @(".bat", ".exe")) {
            $tool = Join-Path $dir.FullName "$Name$ext"
            if (Test-Path $tool) { return $tool }
        }
    }
    return $null
}

function Invoke-Gradle([string[]]$Tasks) {
    $gradleArgs = @($Tasks) + @("--stacktrace", "-PappPatch=$AppPatch", "-PupdateRepo=$UpdateRepo")
    if ($Offline) { $gradleArgs += "--offline" }
    Write-Note ("gradlew " + ($gradleArgs -join " "))
    Push-Location $AndroidDir
    try {
        & (Join-Path $AndroidDir "gradlew.bat") @gradleArgs
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($code -ne 0) {
        Stop-Build "Gradle נכשל (קוד $code)." "הדוחות נמצאים ב-android\app\build\reports. אפשר להריץ שוב עם -Clean."
    }
}

# --- סביבה ---

Write-Step "בדיקת סביבת הבנייה"

foreach ($required in @("gradlew.bat", "gradle\wrapper\gradle-wrapper.jar", "gradle\wrapper\gradle-wrapper.properties", "app\build.gradle.kts")) {
    if (-not (Test-Path (Join-Path $AndroidDir $required))) {
        Stop-Build "חסר android\$required." "צריך להריץ את הסקריפט מתוך עותק מלא של המאגר."
    }
}
if (-not (Test-Path (Join-Path $RepoRoot "pyproject.toml"))) {
    Stop-Build "חסר pyproject.toml בתיקייה הראשית." "ממנו נקבעת גרסת האפליקציה."
}
if (-not (Test-Path (Join-Path $RepoRoot "frontend\app\lang\messages"))) {
    Stop-Build "חסרה התיקייה frontend\app\lang\messages." "ממנה נבנות המחרוזות המתורגמות של האפליקציה."
}

$jdk = Find-Jdk
if (-not $jdk) {
    Stop-Build "לא נמצא JDK בגרסה 17 ומעלה." "התקינו Android Studio, או הגדירו JAVA_HOME לתיקיית JDK 21."
}
$env:JAVA_HOME = $jdk.Home
$env:Path = (Join-Path $jdk.Home "bin") + [IO.Path]::PathSeparator + $env:Path
Write-Ok "JDK $($jdk.Major): $($jdk.Home)"

$sdk = Find-Sdk
if (-not $sdk) {
    Stop-Build "לא נמצא Android SDK." "התקינו אותו דרך Android Studio, או הגדירו ANDROID_HOME."
}
$env:ANDROID_HOME = $sdk
$localProps = Join-Path $AndroidDir "local.properties"
if (-not (Test-Path $localProps)) {
    $escaped = $sdk -replace '\\', '\\' -replace ':', '\:'
    Set-Content -LiteralPath $localProps -Value "sdk.dir=$escaped" -Encoding ASCII
    Write-Note "נוצר android\local.properties"
}
Write-Ok "Android SDK: $sdk"

$versionLine = Select-String -Path (Join-Path $RepoRoot "pyproject.toml") -Pattern '^version\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) { Stop-Build "לא נמצאה שורת version ב-pyproject.toml." }
$mealieVersion = $versionLine.Matches[0].Groups[1].Value
Write-Ok "גרסת Mealie: $mealieVersion"

# Android לא מתקין versionCode נמוך מהמותקן. השחרורים ב-GitHub מתויגים android-v<גרסה>[.<תיקון>],
# אז בנייה מקומית עם appPatch=0 יוצאת ישנה מכל תיקון שכבר פורסם.
if ($AppPatch -lt 0) {
    $propsPatch = (Read-Properties (Join-Path $AndroidDir "gradle.properties"))["appPatch"]
    $AppPatch = [int]$propsPatch
    if ($Offline) {
        Write-Warn "בלי רשת אין בדיקה מול השחרורים ב-GitHub, אז appPatch=$AppPatch מ-gradle.properties."
    }
    else {
        try {
            $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$UpdateRepo/releases?per_page=100" `
                -Headers @{ "User-Agent" = "mealie-android-build"; "Accept" = "application/vnd.github+json" } -TimeoutSec 20
            $pattern = '^android-v' + [regex]::Escape($mealieVersion) + '(?:\.(\d+))?$'
            $published = @($releases | ForEach-Object { $_.tag_name } | Where-Object { $_ -match $pattern } |
                ForEach-Object { if ($_ -match $pattern -and $Matches[1]) { [int]$Matches[1] } else { 0 } })
            if ($published.Count -gt 0) {
                $latest = ($published | Measure-Object -Maximum).Maximum
                $AppPatch = [math]::Max($AppPatch, $latest + 1)
                Write-Note "השחרור האחרון של $mealieVersion ב-$UpdateRepo הוא תיקון $latest"
            }
            else {
                Write-Note "אין עדיין שחרור של $mealieVersion ב-$UpdateRepo"
            }
        }
        catch {
            Write-Warn "לא הצלחתי לבדוק את השחרורים ב-GitHub ($($_.Exception.Message)). נשאר appPatch=$AppPatch."
            Write-Warn "אם במכשיר מותקנת גרסה חדשה יותר, ההתקנה תיחסם. אפשר לקבוע ידנית עם -AppPatch."
        }
    }
}
if ($AppPatch -gt 99) { Stop-Build "appPatch יצא $AppPatch, והמקסימום הוא 99." "קבעו ערך ידנית עם -AppPatch." }
Write-Ok "appPatch: $AppPatch"

# --- חתימה ---

$buildRelease = $BuildType -ne "Debug"
$buildDebug = $BuildType -ne "Release"

if ($buildRelease) {
    Write-Step "חתימת release"
    $keystoreProps = Join-Path $AndroidDir "keystore.properties"
    if (-not $env:MEALIE_KEYSTORE_FILE -and (Test-Path $keystoreProps)) {
        $props = Read-Properties $keystoreProps
        foreach ($key in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
            if (-not $props.ContainsKey($key) -or -not $props[$key]) {
                Stop-Build "ב-android\keystore.properties חסר הערך $key." "צריך את storeFile, storePassword, keyAlias ו-keyPassword."
            }
        }
        $storeFile = $props["storeFile"]
        if (-not [IO.Path]::IsPathRooted($storeFile)) { $storeFile = Join-Path $AndroidDir $storeFile }
        $env:MEALIE_KEYSTORE_FILE = $storeFile
        $env:MEALIE_KEYSTORE_PASSWORD = $props["storePassword"]
        $env:MEALIE_KEY_ALIAS = $props["keyAlias"]
        $env:MEALIE_KEY_PASSWORD = $props["keyPassword"]
        Write-Note "פרטי החתימה נטענו מ-android\keystore.properties"
    }

    if ($env:MEALIE_KEYSTORE_FILE) {
        if (-not (Test-Path -LiteralPath $env:MEALIE_KEYSTORE_FILE)) {
            Stop-Build "קובץ המפתח לא נמצא: $($env:MEALIE_KEYSTORE_FILE)"
        }
        foreach ($name in @("MEALIE_KEYSTORE_PASSWORD", "MEALIE_KEY_ALIAS", "MEALIE_KEY_PASSWORD")) {
            if (-not [Environment]::GetEnvironmentVariable($name)) {
                Stop-Build "המשתנה $name לא מוגדר." "בלי הסיסמה והכינוי אי אפשר לחתום את ה-release."
            }
        }
        Write-Ok "ה-release ייחתם במפתח השחרור, ועדכון עצמי מ-GitHub יעבוד"
    }
    elseif ($RequireSigning) {
        Stop-Build "ביקשתם -RequireSigning, אבל אין מפתח שחרור." "צרו android\keystore.properties או הגדירו את משתני MEALIE_KEYSTORE_*."
    }
    else {
        Write-Warn "אין מפתח שחרור, אז ה-release ייחתם במפתח debug."
        Write-Warn "Android לא יתקין אותו מעל הגרסה הרשמית (חתימה שונה), והוא לא יקבל עדכון עצמי."
        if (-not $PSBoundParameters.ContainsKey("BuildType")) {
            # ל-debug יש מזהה חבילה משלו (.debug), אז הוא מותקן לצד הגרסה הרשמית בלי התנגשות
            $buildDebug = $true
            Write-Note "בונה גם debug, שמותקן לצד הגרסה הרשמית"
        }
    }
}

# --- בנייה ---

$tasks = New-Object System.Collections.Generic.List[string]
if ($Clean) { $tasks.Add("clean") }
# נכשל על תרגום עם placeholders שבורים, לפני שמשקיעים זמן בשאר
$tasks.Add(":app:generateMealieStrings")
if (-not $SkipLint) {
    if ($buildDebug) { $tasks.Add(":app:lintDebug") }
    if ($buildRelease) { $tasks.Add(":app:lintRelease") }
}
if (-not $SkipTests) { $tasks.Add(":app:testDebugUnitTest") }
if ($buildDebug) { $tasks.Add(":app:assembleDebug") }
if ($buildRelease) { $tasks.Add(":app:assembleRelease") }

Write-Step "בנייה עם Gradle (בפעם הראשונה זה לוקח כמה דקות)"
Invoke-Gradle $tasks.ToArray()
Write-Ok "Gradle סיים"

# --- איסוף ואימות ---

Write-Step "איסוף ואימות ה-APK"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$apksigner = Get-LatestBuildTool $sdk "apksigner"
$variants = @()
if ($buildRelease) { $variants += "release" }
if ($buildDebug) { $variants += "debug" }
$collected = @()

foreach ($variant in $variants) {
    $apkDir = Join-Path $AndroidDir "app\build\outputs\apk\$variant"
    $metadataFile = Join-Path $apkDir "output-metadata.json"
    if (-not (Test-Path $metadataFile)) {
        Stop-Build "לא נמצא output-metadata.json ב-$apkDir." "Gradle סיים בלי ליצור APK. אפשר להריץ שוב עם -Clean."
    }
    $metadata = Get-Content -LiteralPath $metadataFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $element = $metadata.elements | Select-Object -First 1
    $source = Join-Path $apkDir $element.outputFile
    if (-not (Test-Path $source)) { Stop-Build "ה-APK לא נמצא: $source" }

    # ל-versionName של debug כבר יש סיומת -debug
    $baseVersion = $element.versionName -replace "-$variant$", ""
    $target = Join-Path $OutputDir ("mealie-android-{0}-{1}.apk" -f $baseVersion, $variant)
    Copy-Item -LiteralPath $source -Destination $target -Force

    if ($apksigner) {
        $output = & $apksigner verify --print-certs $target 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            Stop-Build "אימות החתימה של $target נכשל." ($output -join [Environment]::NewLine)
        }
        $digest = $output | Where-Object { $_ -match 'certificate SHA-256 digest: (\S+)' } | Select-Object -First 1
        if ($digest -match 'digest: (\S+)') { $certSha = $Matches[1] } else { $certSha = "?" }
        $subject = $output | Where-Object { $_ -match 'certificate DN: (.+)$' } | Select-Object -First 1
        $debugSigned = $subject -match 'CN=Android Debug'
    }
    else {
        Write-Warn "apksigner לא נמצא ב-build-tools, אז החתימה לא אומתה"
        $certSha = "?"
        $debugSigned = $variant -eq "debug" -or -not $env:MEALIE_KEYSTORE_FILE
    }

    $collected += [pscustomobject]@{
        Variant     = $variant
        Path        = $target
        Version     = $element.versionName
        VersionCode = $element.versionCode
        SizeMB      = [math]::Round((Get-Item $target).Length / 1MB, 1)
        Sha256      = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        CertSha256  = $certSha
        DebugSigned = $debugSigned
    }
    Write-Ok "$variant → $target"
}

# כולל APK מהרצות קודמות שעדיין בתיקייה
$checksums = Get-ChildItem -LiteralPath $OutputDir -Filter "*.apk" | Sort-Object Name | ForEach-Object {
    "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
}
Set-Content -LiteralPath (Join-Path $OutputDir "SHA256SUMS.txt") -Value $checksums -Encoding ASCII

# --- התקנה ---

if ($Install) {
    Write-Step "התקנה במכשיר"
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { Stop-Build "adb לא נמצא ב-$sdk\platform-tools." }
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if (-not $devices) {
        Stop-Build "אין מכשיר מחובר." "חברו מכשיר עם ניפוי באגים ב-USB או הפעילו אמולטור, ואז הריצו שוב עם -Install."
    }
    if (@($devices).Count -gt 1 -and -not $env:ANDROID_SERIAL) {
        Stop-Build "מחוברים כמה מכשירים." "הגדירו ANDROID_SERIAL למכשיר הרצוי (מהרשימה של adb devices)."
    }
    foreach ($apk in $collected) {
        Write-Note "מתקין $($apk.Variant)…"
        $output = & $adb install -r $apk.Path 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0 -or -not ($output -match 'Success')) {
            $hint = "מה ש-adb החזיר: " + ($output -join " ")
            $incompatible = $output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'
            if ($incompatible -and $apk.Variant -eq "release" -and $apk.DebugSigned) {
                Write-Warn "ה-release חתום במפתח debug, ובמכשיר מותקנת הגרסה הרשמית. דילגתי עליו."
                continue
            }
            if ($incompatible) {
                $hint = "במכשיר מותקנת גרסה עם חתימה אחרת. מסירים אותה (הנתונים שלה יימחקו) ומתקינים שוב."
            }
            elseif ($output -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
                $hint = "במכשיר מותקנת גרסה חדשה יותר. מסירים אותה או מריצים שוב עם -AppPatch גבוה יותר."
            }
            Stop-Build "ההתקנה של $($apk.Variant) נכשלה." $hint
        }
        Write-Ok "$($apk.Variant) הותקן"
    }
}

# --- סיכום ---

$elapsed = (Get-Date) - $Started
Write-Host ""
Write-Host ("הבנייה הסתיימה תוך {0:mm\:ss} דקות" -f $elapsed) -ForegroundColor Green
foreach ($apk in $collected) {
    Write-Host ""
    Write-Host ("  {0}  {1} (קוד {2}), {3} MB" -f $apk.Variant, $apk.Version, $apk.VersionCode, $apk.SizeMB)
    Write-Host "  $($apk.Path)"
    Write-Host "  SHA-256: $($apk.Sha256)"
    if ($apk.CertSha256 -ne "?") { Write-Host "  תעודת חתימה: $($apk.CertSha256)" }
    if ($apk.Variant -eq "release" -and $apk.DebugSigned) {
        Write-Warn "חתום במפתח debug: לא יותקן מעל הגרסה הרשמית. לבדיקה במכשיר, התקינו את ה-debug."
    }
}

if ($Open) { Invoke-Item $OutputDir }
exit 0
