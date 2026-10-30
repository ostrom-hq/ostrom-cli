# Ostrom installer for Windows:  irm https://ostrom.run/install.ps1 | iex
#
# Environment:
#   OSTROM_VERSION          version to install (default: the latest, read from latest.json)
#   OSTROM_INSTALL_DIR      where to put ostrom.exe (default: %USERPROFILE%\.ostrom\bin); no admin rights needed
#   OSTROM_DOWNLOAD_BASE    releases URL (default: https://github.com/ostrom-hq/ostrom-cli/releases)
#   OSTROM_NO_MODIFY_PATH   set to 1 to leave your user PATH alone
#   OSTROM_SKIP_COSIGN      set to 1 to skip the signature check even when cosign is installed
#
# The SHA-256 of the archive is always checked against SHA256SUMS. When cosign is installed, the signature of
# SHA256SUMS is checked too, against the key pinned below.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$CosignPub = @'
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENbn5GmwW348HxCtTPQbNfcvHmSZG
G+IBNKubzv51TIua776a3875eoe5isQYKYmVwe5yTUU0v2k8Pu7WXQWoBg==
-----END PUBLIC KEY-----
'@

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
    $rel = "$base/download/v$version"
    Write-Host "Installing Ostrom $version (windows-x64)..."
    Invoke-WebRequest -Uri "$rel/SHA256SUMS" -OutFile (Join-Path $work 'SHA256SUMS') -UseBasicParsing
    Invoke-WebRequest -Uri "$rel/$archive" -OutFile (Join-Path $work $archive) -UseBasicParsing

    $line = Get-Content (Join-Path $work 'SHA256SUMS') | Where-Object { $_ -match "^([0-9a-fA-F]{64})\s+\*?$([regex]::Escape($archive))$" } | Select-Object -First 1
    if (-not $line) { throw "ostrom install: checksum for $archive not found in SHA256SUMS: refusing to install" }
    $expected = ($line -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $work $archive)).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "ostrom install: checksum mismatch for $archive (expected $expected, got $actual): refusing to install" }

    if ($env:OSTROM_SKIP_COSIGN -ne '1' -and (Get-Command cosign -ErrorAction SilentlyContinue)) {
      Invoke-WebRequest -Uri "$rel/SHA256SUMS.sig" -OutFile (Join-Path $work 'SHA256SUMS.sig') -UseBasicParsing
      Set-Content -Path (Join-Path $work 'cosign.pub') -Value $CosignPub
      & cosign verify-blob --key (Join-Path $work 'cosign.pub') --insecure-ignore-tlog --signature (Join-Path $work 'SHA256SUMS.sig') (Join-Path $work 'SHA256SUMS') *> $null
      if ($LASTEXITCODE -ne 0) { throw 'ostrom install: the signature of SHA256SUMS does not verify: refusing to install' }
      Write-Host 'Signature verified with cosign.'
    }

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
