param(
  [string]$Base = 'http://127.0.0.1:4273/admin',
  [int]$Width = 1440,
  [int]$Height = 1400,
  [string]$Prefix = 'r',
  [int]$PortBase = 9400,
  [string[]]$Pages = @('dashboard','orgs','users','codes','rooms','photos','audit','ops','settings'),
  [string]$OutDir = 'E:\0workspace\dshnext\Marches\GAKJ\weilie-platform\docs\redesign'
)
$ErrorActionPreference = 'Stop'
$shot = Join-Path $PSScriptRoot 'cdp-shot.ps1'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$port = $PortBase
foreach ($p in $Pages) {
  $js = @"
(async () => {
  const setVal = (el, v) => { const d = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value'); d.set.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
  const inputs = document.querySelectorAll('input');
  setVal(inputs[0], 'orgadmin'); setVal(inputs[1], 'org12345');
  await new Promise(r => setTimeout(r, 250));
  const btn = [...document.querySelectorAll('button')].find(b => (b.textContent || '').trim() === '登录');
  if (!btn) return 'NO_BUTTON';
  btn.click();
  await new Promise(r => setTimeout(r, 1300));
  history.pushState({}, '', '/admin/$p');
  window.dispatchEvent(new PopStateEvent('popstate'));
  await new Promise(r => setTimeout(r, 2200));
  return document.title + ' | ' + location.pathname;
})()
"@
  $out = Join-Path $OutDir ("{0}-{1}-{2}.png" -f $Prefix, $p, $Width)
  & $shot -Url "$Base/login" -Out $out -WaitMs 2200 -Height $Height -Width $Width -Port $port -Js $js | Select-Object -Last 1
  $port++
}