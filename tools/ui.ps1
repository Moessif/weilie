$ErrorActionPreference = "Stop"
$script:Adb = "C:\AndroidDev\android-sdk\platform-tools\adb.exe"
$script:Shots = "E:\0workspace\dshnext\Marches\GAKJ\weilie\dist\screens"

function Shot([string]$name) {
    & $script:Adb exec-out screencap -p > (Join-Path $script:Shots "$name.png")
    Write-Output "shot $name"
}

function DumpUi {
    & $script:Adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    return ((& $script:Adb shell cat /sdcard/ui.xml) -join '')
}

function BoundsOf([string]$text) {
    $raw = DumpUi
    $m = [regex]::Matches($raw, '<node[^>]*text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($x in $m) {
        $l = [int]$x.Groups[1].Value; $t = [int]$x.Groups[2].Value
        $r = [int]$x.Groups[3].Value; $b = [int]$x.Groups[4].Value
        Write-Output "$text [$l,$t][$r,$b] center=$([int](($l+$r)/2)),$([int](($t+$b)/2))"
    }
}

function TapText([string]$text) {
    $raw = DumpUi
    $m = [regex]::Matches($raw, '<node[^>]*text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Count -eq 0) { Write-Output "MISS $text"; return $false }
    $x = $m[0]
    $l = [int]$x.Groups[1].Value; $t = [int]$x.Groups[2].Value
    $r = [int]$x.Groups[3].Value; $b = [int]$x.Groups[4].Value
    $cx = [int](($l + $r) / 2); $cy = [int](($t + $b) / 2)
    & $script:Adb shell input tap $cx $cy | Out-Null
    Write-Output "tap $text ($cx,$cy)"
    return $true
}

function CrashTail {
    $out = & $script:Adb logcat -d -b crash -t 80
    return ($out -join "`n")
}
