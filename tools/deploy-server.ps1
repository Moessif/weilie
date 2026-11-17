param(
  [ValidateSet("ssh", "workbench")][string]$Mode = "ssh",
  [string]$User = "root",
  [string]$HostName = "47.89.181.85",
  [string]$KeyPath = "",
  [string]$Jar = "D:\weilie-server\build\libs\weilie-server-all.jar",
  [string]$RemoteJar = "/opt/weilie/weilie-server-all.jar",
  [string]$Service = "weilie.service",
  [switch]$SkipBackup
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Jar)) { throw "jar not found: $Jar (run 'gradle fatJar' in server/ first)" }
$hash = (Get-FileHash $Jar -Algorithm SHA256).Hash
Write-Host ("jar: {0}  {1:N2} MB  SHA256={2}" -f $Jar, ((Get-Item $Jar).Length / 1MB), $hash)

function Copy-ToServer {
  if ($Mode -eq "workbench") {
    Write-Host "== workbench upload =="
    & workbench upload $Jar $RemoteJar -i "i-0xieiymktekjfhe5b80q" -r "us-east-1" -f
    if ($LASTEXITCODE -ne 0) { throw "workbench upload failed" }
  } else {
    Write-Host "== scp =="
    $scpArgs = @("-P", "22")
    if ($KeyPath) { $scpArgs += @("-i", $KeyPath) }
    $scpArgs += @($Jar, "${User}@${HostName}:/tmp/weilie-server-new.jar")
    & scp @scpArgs
    if ($LASTEXITCODE -ne 0) { throw "scp failed" }
  }
}

function Invoke-Remote([string]$Command) {
  if ($Mode -eq "workbench") {
    & workbench exec -i "i-0xieiymktekjfhe5b80q" -r "us-east-1" --command $Command
    if ($LASTEXITCODE -ne 0) { throw "workbench exec failed: $Command" }
  } else {
    $sshArgs = @("-o", "StrictHostKeyChecking=accept-new")
    if ($KeyPath) { $sshArgs += @("-i", $KeyPath) }
    $sshArgs += @("${User}@${HostName}", $Command)
    & ssh @sshArgs
    if ($LASTEXITCODE -ne 0) { throw "ssh failed: $Command" }
  }
}

Copy-ToServer

$backup = if ($SkipBackup) { "" } else { 'cp -a ' + $RemoteJar + ' ' + $RemoteJar + '.bak.$(date +%Y%m%d%H%M) 2>/dev/null || true; ' }
$remote = 'set -e; ' + $backup +
  'if [ -f /tmp/weilie-server-new.jar ]; then mv /tmp/weilie-server-new.jar ' + $RemoteJar + '; fi; ' +
  'chmod 644 ' + $RemoteJar + '; ' +
  'systemctl restart ' + $Service + '; sleep 2; systemctl is-active ' + $Service
Invoke-Remote $remote

Write-Host "== health check =="
for ($i = 0; $i -lt 10; $i++) {
  try {
    $r = Invoke-WebRequest -Uri "http://$HostName:8080/api/health" -UseBasicParsing -TimeoutSec 5
    Write-Host ("  /api/health -> {0} {1}" -f $r.StatusCode, $r.Content)
    if ($r.StatusCode -eq 200) { Write-Host "deploy done"; exit 0 }
  } catch {
    Start-Sleep -Seconds 2
  }
}
throw "health check failed, check /var/log/weilie.log on the server"
