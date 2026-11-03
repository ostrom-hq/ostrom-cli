# Ostrom installer for Windows:  irm https://ostrom.run/install.ps1 | iex
#
# Environment:
#   OSTROM_VERSION          version to install (default: the latest, read from latest.json)
#   OSTROM_INSTALL_DIR      where to put ostrom.exe (default: %USERPROFILE%\.ostrom\bin); no admin rights needed
#   OSTROM_DOWNLOAD_BASE    releases URL (default: https://github.com/ostrom-hq/ostrom-cli/releases)
#   OSTROM_NO_MODIFY_PATH   set to 1 to leave your user PATH alone
#   OSTROM_SKIP_COSIGN      set to 1 to skip the signature check of SHA256SUMS
#
# SHA256SUMS is checked against its signature and the key pinned below, with cosign when it is installed, else with
# .NET (cosign signs a blob as ECDSA P-256 over its SHA-256; Windows PowerShell 5.1 and PowerShell 7 both check it). The
# archive is then checked against SHA256SUMS.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$CosignPub = @'
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENbn5GmwW348HxCtTPQbNfcvHmSZG
G+IBNKubzv51TIua776a3875eoe5isQYKYmVwe5yTUU0v2k8Pu7WXQWoBg==
-----END PUBLIC KEY-----
'@

# The coordinates X || Y (64 bytes) of a P-256 public key in PEM (SubjectPublicKeyInfo), after its fixed header.
function Get-P256Point([string]$Pem) {
  $der = [Convert]::FromBase64String(($Pem -replace '-----[A-Z ]+-----', '' -replace '\s', ''))
  $header = [byte[]](0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00,0x04)
  if ($der.Length -ne 91 -or [Convert]::ToBase64String([byte[]]$der[0..26]) -ne [Convert]::ToBase64String($header)) {
    throw 'ostrom install: the pinned key is not a P-256 public key'
  }
  return ,$der[27..90]
}

# A DER ECDSA signature (SEQUENCE of the integers r and s) as the 64 bytes r || s that .NET's VerifyData reads.
function ConvertFrom-DerSignature([byte[]]$Der) {
  $fail = 'ostrom install: the signature of SHA256SUMS is not readable: refusing to install'
  if ($Der.Length -lt 8 -or $Der[0] -ne 0x30 -or $Der[1] -ne ($Der.Length - 2)) { throw $fail }
  $out = New-Object byte[] 64
  $at = 2
  foreach ($half in 0, 32) {
    if ($Der[$at] -ne 0x02) { throw $fail }
    $len = $Der[$at + 1]
    $int = $Der[($at + 2)..($at + 1 + $len)]
    $at += 2 + $len
    while ($int.Length -gt 32 -and $int[0] -eq 0) { $int = $int[1..($int.Length - 1)] }
    if ($int.Length -gt 32) { throw $fail }
    [Array]::Copy([byte[]]$int, 0, $out, $half + 32 - $int.Length, $int.Length)
  }
  if ($at -ne $Der.Length) { throw $fail }
  return ,$out
}

# SHA256SUMS against its signature and the pinned key; throws when it does not verify.
function Test-Signature([string]$Rel, [string]$Work) {
  if ($env:OSTROM_SKIP_COSIGN -eq '1') {
    Write-Host 'Signature check skipped (OSTROM_SKIP_COSIGN=1): only the checksum is checked.'
    return
  }
  $sums = Join-Path $Work 'SHA256SUMS'
  $sig = Join-Path $Work 'SHA256SUMS.sig'
  try { Invoke-WebRequest -Uri "$Rel/SHA256SUMS.sig" -OutFile $sig -UseBasicParsing }
  catch { throw 'ostrom install: SHA256SUMS.sig could not be downloaded: refusing to install' }
  if (Get-Command cosign -ErrorAction SilentlyContinue) {
    Set-Content -Path (Join-Path $Work 'cosign.pub') -Value $CosignPub
    & cosign verify-blob --key (Join-Path $Work 'cosign.pub') --insecure-ignore-tlog --signature $sig $sums *> $null
    if ($LASTEXITCODE -ne 0) { throw 'ostrom install: the signature of SHA256SUMS does not verify: refusing to install' }
    Write-Host 'Signature verified with cosign.'
    return
  }
  try { $der = [Convert]::FromBase64String(((Get-Content -Raw $sig) -replace '\s', '')) }
  catch { throw 'ostrom install: the signature of SHA256SUMS is not readable: refusing to install' }
  $point = Get-P256Point $CosignPub
  $parameters = New-Object System.Security.Cryptography.ECParameters
  $parameters.Curve = [System.Security.Cryptography.ECCurve+NamedCurves]::nistP256
  $q = New-Object System.Security.Cryptography.ECPoint
  $q.X = [byte[]]$point[0..31]
  $q.Y = [byte[]]$point[32..63]
  $parameters.Q = $q
  $ecdsa = [System.Security.Cryptography.ECDsa]::Create($parameters)
  $data = [IO.File]::ReadAllBytes($sums)
  $ok = $ecdsa.VerifyData($data, (ConvertFrom-DerSignature $der), [System.Security.Cryptography.HashAlgorithmName]::SHA256)
  if (-not $ok) { throw 'ostrom install: the signature of SHA256SUMS does not verify: refusing to install' }
  Write-Host 'Signature verified.'
}

function Install-Ostrom {
  $base = if ($env:OSTROM_DOWNLOAD_BASE) { $env:OSTROM_DOWNLOAD_BASE.TrimEnd('/') } else { 'https://github.com/ostrom-hq/ostrom-cli/releases' }
  $installDir = if ($env:OSTROM_INSTALL_DIR) { $env:OSTROM_INSTALL_DIR } else { Join-Path $env:USERPROFILE '.ostrom\bin' }

  # Windows on ARM runs the x64 build under emulation; only 32-bit systems are unsupported.
  if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'ostrom install: unsupported platform: 32-bit Windows (supported: windows-x64)'
  }

  $work = Join-Path ([IO.Path]::GetTempPath()) ("ostrom-install-" + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $work | Out-Null
  try {
    $version = $env:OSTROM_VERSION
    if (-not $version) {
      Write-Host 'Looking up the latest version...'
      $latest = Invoke-RestMethod -Uri "$base/latest/download/latest.json"
      $version = $latest.version
      if (-not $version) { throw 'ostrom install: latest.json has no version' }
    }
    $version = $version.TrimStart('v')
    if ($version -notmatch '^[0-9A-Za-z.+_-]+$') { throw "ostrom install: invalid version: $version" }

    $archive = "ostrom-$version-windows-x64.zip"
    $rel = "$base/download/$version"
    Write-Host "Installing Ostrom $version (windows-x64)..."
    Invoke-WebRequest -Uri "$rel/SHA256SUMS" -OutFile (Join-Path $work 'SHA256SUMS') -UseBasicParsing
    Invoke-WebRequest -Uri "$rel/$archive" -OutFile (Join-Path $work $archive) -UseBasicParsing

    Test-Signature $rel $work
    $line = Get-Content (Join-Path $work 'SHA256SUMS') | Where-Object { $_ -match "^([0-9a-fA-F]{64})\s+\*?$([regex]::Escape($archive))$" } | Select-Object -First 1
    if (-not $line) { throw "ostrom install: checksum for $archive not found in SHA256SUMS: refusing to install" }
    $expected = ($line -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $work $archive)).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "ostrom install: checksum mismatch for $archive (expected $expected, got $actual): refusing to install" }

    $unpack = Join-Path $work 'unpack'
    Expand-Archive -Path (Join-Path $work $archive) -DestinationPath $unpack
    $exe = Join-Path $unpack 'ostrom.exe'
    if (-not (Test-Path $exe)) { throw "ostrom install: $archive does not contain ostrom.exe" }
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    Move-Item -Force $exe (Join-Path $installDir 'ostrom.exe')
    Write-Host "Installed $(Join-Path $installDir 'ostrom.exe')"

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries = @(); if ($userPath) { $entries = $userPath -split ';' }
    if ($entries -notcontains $installDir) {
      if ($env:OSTROM_NO_MODIFY_PATH -eq '1') {
        Write-Host "Add Ostrom to your PATH: $installDir"
      } else {
        $newPath = if ($userPath) { "$userPath;$installDir" } else { $installDir }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Host "Added $installDir to your user PATH. Open a new terminal to use it."
      }
    }
    Write-Host 'Then run: ostrom login'
  }
  finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
  }
}

Install-Ostrom
