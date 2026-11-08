param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$ExtraJson = "{}",
  [string]$Region = "us-east-1"
)
$ErrorActionPreference = "Stop"
$Extra = @{}
$o = $ExtraJson | ConvertFrom-Json
$o.PSObject.Properties | ForEach-Object { $Extra[$_.Name] = [string]$_.Value }
$cfgPath = Join-Path $env:USERPROFILE ".workbench\config.json"
$cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
$prof = $cfg.profiles.($cfg.current)
$ak = $prof.access_key_id
$sk = $prof.access_key_secret
if (-not $ak) { throw "no access_key_id in workbench config" }

function PEnc([string]$s) {
  $r = [System.Uri]::EscapeDataString($s)
  $r = $r -replace '\+', '%20' -replace '\*', '%2A' -replace '%7E', '~'
  return $r
}

$params = @{
  Action = $Action
  Version = "2014-05-26"
  Format = "JSON"
  AccessKeyId = $ak
  SignatureMethod = "HMAC-SHA1"
  SignatureVersion = "1.0"
  SignatureNonce = [guid]::NewGuid().ToString("N")
  Timestamp = [datetime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
  RegionId = $Region
}
foreach ($k in $Extra.Keys) { $params[$k] = $Extra[$k] }

$sorted = $params.GetEnumerator() | Sort-Object Name
$canon = ($sorted | ForEach-Object { (PEnc $_.Name) + "=" + (PEnc ([string]$_.Value)) }) -join "&"
$stringToSign = "GET&" + (PEnc "/") + "&" + (PEnc $canon)
$hmac = New-Object System.Security.Cryptography.HMACSHA1
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($sk + "&")
$sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($stringToSign)))
$url = "https://ecs.$Region.aliyuncs.com/?$canon&Signature=" + (PEnc $sig)

$resp = Invoke-WebRequest -Uri $url -TimeoutSec 20 -UseBasicParsing
return $resp.Content
