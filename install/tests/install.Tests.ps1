# Pester 5 tests of install.ps1 against a fake release served by python on localhost (windows-latest).
BeforeAll {
  $script:root = Join-Path ([IO.Path]::GetTempPath()) ("ostrom-pester-" + [Guid]::NewGuid().ToString('N'))
  $script:site = Join-Path $root 'site'
  New-Item -ItemType Directory -Force -Path $root | Out-Null

  # The release's cosign key, played by a key made here: the installer under test is install.ps1 with this public key
  # in place of the pinned one. cosign signs a blob as ECDSA P-256 over its SHA-256, DER, in base64.
  $script:key = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve+NamedCurves]::nistP256)
  $pem = "-----BEGIN PUBLIC KEY-----`n" + [Convert]::ToBase64String($key.ExportSubjectPublicKeyInfo()) + "`n-----END PUBLIC KEY-----"
  $source = Get-Content -Raw (Join-Path $PSScriptRoot '..\install.ps1')
  $patched = [regex]::Replace($source, "(?s)(\`$CosignPub = @')\r?\n.*?\r?\n('@)", { param($m) $m.Groups[1].Value + "`n" + $pem + "`n" + $m.Groups[2].Value })
  if ($patched -eq $source) { throw 'the test key was not put in the installer' }
  $script:installer = Join-Path $root 'install.ps1'
  Set-Content -Path $installer -Value $patched

  function Set-Signature([string]$Dir) {
    $sums = Join-Path $Dir 'SHA256SUMS'
    $signature = $key.SignData([IO.File]::ReadAllBytes($sums), [System.Security.Cryptography.HashAlgorithmName]::SHA256,
      [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
    Set-Content -Path "$sums.sig" -Value ([Convert]::ToBase64String($signature)) -NoNewline
  }

  function New-Release([string]$Version) {
    $dir = Join-Path $site "download\$Version"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $pkg = Join-Path $root "pkg-$Version"
    New-Item -ItemType Directory -Force -Path $pkg | Out-Null
    Set-Content -Path (Join-Path $pkg 'ostrom.exe') -Value "fake ostrom $Version"
    Set-Content -Path (Join-Path $pkg 'LICENSE') -Value 'Apache'
    $zip = Join-Path $dir "ostrom-$Version-windows-x64.zip"
    Compress-Archive -Path (Join-Path $pkg '*') -DestinationPath $zip
    $hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
    Set-Content -Path (Join-Path $dir 'SHA256SUMS') -Value "$hash  ostrom-$Version-windows-x64.zip"
    Set-Signature $dir
    return $dir
  }

  New-Release '1.2.3' | Out-Null
  New-Release '1.3.0' | Out-Null
  $bad = New-Release '2.0.0'
  Set-Content -Path (Join-Path $bad 'SHA256SUMS') -Value ('0' * 64 + '  ostrom-2.0.0-windows-x64.zip')
  Set-Signature $bad
  $missing = New-Release '2.1.0'
  Set-Content -Path (Join-Path $missing 'SHA256SUMS') -Value ('0' * 64 + '  other.zip')
  Set-Signature $missing
  # SHA256SUMS changed after it was signed, to match a changed archive
  $changed = New-Release '2.3.0'
  $zip = Join-Path $changed 'ostrom-2.3.0-windows-x64.zip'
  Add-Content -Path $zip -Value 'tampered'
  $hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
  Set-Content -Path (Join-Path $changed 'SHA256SUMS') -Value "$hash  ostrom-2.3.0-windows-x64.zip"
  # no signature at all
  $unsigned = New-Release '2.4.0'
  Remove-Item (Join-Path $unsigned 'SHA256SUMS.sig')
  New-Item -ItemType Directory -Force -Path (Join-Path $site 'latest\download') | Out-Null
  Set-Content -Path (Join-Path $site 'latest\download\latest.json') -Value '{"version":"1.3.0","date":"2026-10-30T10:00:00Z","assets":{}}'

  $script:port = Get-Random -Minimum 20000 -Maximum 40000
  $script:server = Start-Process -FilePath python -ArgumentList '-m', 'http.server', $port, '--bind', '127.0.0.1' `
    -WorkingDirectory $site -PassThru -WindowStyle Hidden
  for ($i = 0; $i -lt 50; $i++) {
    try { Invoke-WebRequest -Uri "http://127.0.0.1:$port/latest/download/latest.json" -UseBasicParsing | Out-Null; break }
    catch { Start-Sleep -Milliseconds 200 }
  }

  function Invoke-Installer([hashtable]$Env) {
    $dest = Join-Path $root ("dest-" + [Guid]::NewGuid().ToString('N'))
    $all = @{
      OSTROM_DOWNLOAD_BASE = "http://127.0.0.1:$port"
      OSTROM_INSTALL_DIR = $dest
      OSTROM_NO_MODIFY_PATH = '1'
    } + $Env
    $saved = @{}
    foreach ($k in $all.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $all[$k]) }
    try { & $installer *>&1 | Out-Null; $ok = $true } catch { $ok = $false; $err = $_.Exception.Message }
    finally { foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) } }
    return [pscustomobject]@{ Ok = $ok; Error = $err; Dest = $dest; Exe = (Join-Path $dest 'ostrom.exe') }
  }
}

AfterAll {
  if ($server) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
  Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}

Describe 'install.ps1' {
  It 'installs a pinned version' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '1.2.3' }
    $r.Ok | Should -BeTrue
    Get-Content $r.Exe | Should -Be 'fake ostrom 1.2.3'
  }
  It 'accepts a leading v' {
    (Invoke-Installer @{ OSTROM_VERSION = 'v1.2.3' }).Ok | Should -BeTrue
  }
  It 'resolves the latest version through latest.json' {
    $r = Invoke-Installer @{}
    $r.Ok | Should -BeTrue
    Get-Content $r.Exe | Should -Be 'fake ostrom 1.3.0'
  }
  It 'refuses a wrong checksum and installs nothing' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '2.0.0' }
    $r.Ok | Should -BeFalse
    $r.Error | Should -Match 'checksum mismatch'
    Test-Path $r.Exe | Should -BeFalse
  }
  It 'refuses an archive absent from SHA256SUMS' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '2.1.0' }
    $r.Ok | Should -BeFalse
    Test-Path $r.Exe | Should -BeFalse
  }
  It 'fails on an unknown version' {
    (Invoke-Installer @{ OSTROM_VERSION = '9.9.9' }).Ok | Should -BeFalse
  }
  It 'checks the signature of SHA256SUMS without cosign' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '1.2.3' }
    $r.Ok | Should -BeTrue
  }
  It 'refuses a SHA256SUMS changed after it was signed, and installs nothing' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '2.3.0' }
    $r.Ok | Should -BeFalse
    $r.Error | Should -Match 'signature'
    Test-Path $r.Exe | Should -BeFalse
  }
  It 'refuses a release without its signature' {
    $r = Invoke-Installer @{ OSTROM_VERSION = '2.4.0' }
    $r.Ok | Should -BeFalse
    $r.Error | Should -Match 'SHA256SUMS.sig'
    Test-Path $r.Exe | Should -BeFalse
  }
  It 'skips the signature check when asked, the checksum still checked' {
    (Invoke-Installer @{ OSTROM_VERSION = '2.3.0'; OSTROM_SKIP_COSIGN = '1' }).Ok | Should -BeTrue
    (Invoke-Installer @{ OSTROM_VERSION = '2.0.0'; OSTROM_SKIP_COSIGN = '1' }).Ok | Should -BeFalse
  }
  It 'reads a DER signature whose integers carry a leading zero or are short' {
    . ([scriptblock]::Create((Get-Content -Raw $installer) -replace '(?m)^Install-Ostrom\s*$', ''))
    $r = [byte[]](@(0x00, 0x80) + @(1) * 31)
    $s = [byte[]](@(5) * 31)
    $der = [byte[]](@(0x30, (2 + $r.Length + 2 + $s.Length), 0x02, $r.Length) + $r + @(0x02, $s.Length) + $s)
    $p1363 = ConvertFrom-DerSignature $der
    $p1363.Length | Should -Be 64
    $p1363[0] | Should -Be 0x80
    $p1363[32] | Should -Be 0
    $p1363[33] | Should -Be 5
    { ConvertFrom-DerSignature ([byte[]](0x30, 0x02, 0x05, 0x00)) } | Should -Throw
  }
  It 'refuses a path-like version' {
    (Invoke-Installer @{ OSTROM_VERSION = '1.2.3/../../x' }).Ok | Should -BeFalse
  }
}
