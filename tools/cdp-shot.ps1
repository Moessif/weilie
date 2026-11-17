param(
  [Parameter(Mandatory=$true)][string]$Url,
  [Parameter(Mandatory=$true)][string]$Out,
  [string]$Js = "",
  [int]$WaitMs = 3500,
  [int]$Port = 9333,
  [hashtable]$Cookies = @{},
  [int]$Width = 1440,
  [int]$Height = 1000
)
$ErrorActionPreference = 'Stop'
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$profile = Join-Path $env:TEMP ("cdp-profile-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $profile | Out-Null
$proc = Start-Process -FilePath $chrome -PassThru -ArgumentList @(
  '--headless=new','--disable-gpu','--hide-scrollbars','--no-first-run','--no-default-browser-check',
  "--remote-debugging-port=$Port","--user-data-dir=$profile","--window-size=$Width,$Height",'about:blank'
)
$ws = $null
try {
  $targets = $null
  for($i=0;$i -lt 30;$i++){
    try { $targets = Invoke-RestMethod "http://127.0.0.1:$Port/json" -TimeoutSec 2; if($targets){ break } } catch { Start-Sleep -Milliseconds 300 }
  }
  if(-not $targets){ throw 'cdp target not available' }
  $page = $targets | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
  if(-not $page){ throw 'no page target' }
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  $ct = [System.Threading.CancellationToken]::None
  $ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $ct).Wait()
  $script:nextId = 0
  function Send-Cdp([hashtable]$payload){
    $payload.id = (++$script:nextId)
    $json = $payload | ConvertTo-Json -Compress -Depth 12
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $seg = [ArraySegment[byte]]::new($bytes)
    $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait()
    $buf = New-Object byte[] 1048576
    while ($true) {
      $sb = New-Object System.Text.StringBuilder
      do {
        $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $ct).Result
        [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $r.Count))
      } while (-not $r.EndOfMessage)
      $msg = $sb.ToString() | ConvertFrom-Json
      if ($msg.PSObject.Properties.Name -contains 'id' -and $msg.id -eq $payload.id) { return $msg }
      $script:events += $msg
    }
  }
  $script:events = @()
  [void](Send-Cdp @{ method='Log.enable' })
  [void](Send-Cdp @{ method='Page.enable' })
  [void](Send-Cdp @{ method='Network.enable' })
  foreach($k in $Cookies.Keys){
    $ck = Send-Cdp @{ method='Network.setCookie'; params=@{ name=$k; value=$Cookies[$k]; url=$Url; path='/'; httpOnly=$true; secure=$false } }
    "cookie set $k ok=$($ck.result.success)"
  }
  [void](Send-Cdp @{ method='Runtime.enable' })
  [void](Send-Cdp @{ method='Emulation.setDeviceMetricsOverride'; params=@{ width=$Width; height=$Height; deviceScaleFactor=1; mobile=$false } })
  [void](Send-Cdp @{ method='Page.navigate'; params=@{ url=$Url } })
  Start-Sleep -Milliseconds $WaitMs
  [void](Send-Cdp @{ method='Runtime.evaluate'; params=@{ expression='1' } })
  $interesting = $script:events | Where-Object { $_.method -in @('Log.entryAdded','Runtime.exceptionThrown','Runtime.consoleAPICalled') } | Select-Object -First 12
  foreach($ev in $interesting){ "EVENT " + $ev.method + " : " + (($ev.params | ConvertTo-Json -Compress -Depth 8)) }
  if($Js -ne ""){
    $res = Send-Cdp @{ method='Runtime.evaluate'; params=@{ expression=$Js; awaitPromise=$true; returnByValue=$true; userGesture=$true } }
    if($res.result.exceptionDetails){ "JS exception: " + ($res.result.exceptionDetails | ConvertTo-Json -Compress -Depth 6) } else { "JS result: " + ($res.result.result.value | ConvertTo-Json -Compress -Depth 6) }
    Start-Sleep -Milliseconds 2500
  }
  $shot = Send-Cdp @{ method='Page.captureScreenshot'; params=@{ format='png'; captureBeyondViewport=$false } }
  [System.IO.File]::WriteAllBytes($Out, [Convert]::FromBase64String($shot.result.data))
  "shot: $Out"
} finally {
  if($ws){ try { $ws.Dispose() } catch {} }
  try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
  try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch {}
}
