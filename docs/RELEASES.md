# Releases

How the `ostrom` CLI is released, what is signed, and how to check it.

## Cutting a release

1. Make sure `main` is green (`CI` and `Release checks`).
2. Tag and push: `git tag X.Y.Z && git push origin X.Y.Z` (bare semver, no `v`, CONVENTIONS section 12) (from `main`).
3. The `Release` workflow builds the four targets (linuxX64, linuxArm64, mingwX64 on one Ubuntu job, macosArm64 on one
   macOS job, the only expensive one), packs, signs, builds the runner image, and creates the GitHub Release.

A pre-release is any version with a dash (`1.0.0-rc.1`): the release is marked as such.

**Dry run.** Run the workflow by hand (`Actions > Release > Run workflow`, or
`gh workflow run release.yml`). It does everything except publish: no GitHub Release, no image push, no tap push.
Without a `version`, the label is the version floor's (`1.0.0-dryrun` for a floor of 1.0.0): a label under the floor
stops the manifest step, as a real release under it would. The packed and signed files are in the `release-dist`
artifact of the run. A dry run reads no signing secret: it runs on any branch and anyone can download its artifacts,
so cosign and the manifest are signed with throwaway keys made in the job (checked against them, openssl included),
and the macOS and Windows binaries are not code-signed. Only a tag signs with the release keys.

**Who can cut a release.** The tag ruleset `release-tags` lets only organisation admins create, move or delete a tag
`[0-9]*` or `v[0-9]*`. The jobs that sign or publish (`build-macos`, `package`, `image`, `publish`, `taps`) run in the
`release` environment, which admits only those tags; a dry run enters no environment. The signing keys and the tap
deploy keys belong in that environment's secrets, not the repository's: a job outside it, a dry run or another
workflow, then cannot read them.

**Draft or public.** While the repository is private the release is created as a **draft** (only the team sees it).
At the go: make the repositories public, set the repository variable `RELEASE_DRAFT` to `false`
(`gh variable set RELEASE_DRAFT --body false -R ostrom-hq/ostrom-cli`), and publish the existing draft
(`gh release edit X.Y.Z --draft=false`) or cut the next tag. With `RELEASE_DRAFT=false` the next release is public
at once and the Homebrew tap and Scoop bucket are updated (`taps` job). The image is on Docker Hub
(`docker.io/ostromhq`), public.

## What a release contains

| File | What |
|---|---|
| `ostrom-<version>-linux-x64.tar.gz`, `-linux-arm64.tar.gz`, `-macos-arm64.tar.gz` | `ostrom` + `LICENSE` |
| `ostrom-<version>-windows-x64.zip` | `ostrom.exe` + `LICENSE` |
| `<archive>.sig` | cosign signature of the archive (base64) |
| `SHA256SUMS`, `SHA256SUMS.sig` | checksums of the archives and SBOMs, and the cosign signature of that file |
| `ostrom-<version>-<target>.spdx.json` | SBOM (SPDX JSON, syft) of each binary |
| `latest.json`, `latest.json.sig` | update manifest and its Ed25519 signature |
| `install.sh`, `install.ps1` | the installers (the site serves `install.sh` at `https://ostrom.run/install.sh`) |
| `winget-manifests-<version>.tar.gz` | the three winget manifest files, to submit to `microsoft/winget-pkgs` after the go |

Also published: the images `docker.io/ostromhq/ostrom-runner:<version>` and
`docker.io/ostromhq/ostrom-session:<version>` (linux/amd64 and linux/arm64, signed by digest, with an SBOM and
max-mode provenance attached by buildx; `:latest` moves to them when ostrom-server stops publishing its own runner
image), the Homebrew formula
(`ostrom-hq/homebrew-tap`, `Formula/ostrom.rb`) and the Scoop manifest (`ostrom-hq/scoop-bucket`, `bucket/ostrom.json`).
The `taps` job pushes them over SSH trusting only GitHub's published host keys, pinned in
`scripts/release/github_known_hosts` (no first-use trust, no system `known_hosts`); it takes its ssh options from
`scripts/release/check-github-host-keys.sh --ssh-options`. The same script checks that every pinned key is still
published at https://api.github.com/meta and that each pinned key type verifies github.com: in the release checks,
and in the release's `version` job before a public release is published (`RELEASE_DRAFT` set to `false`: a draft
release, and a dry run, run no taps job and skip the check). When GitHub rotates a key, run
`sh scripts/release/check-github-host-keys.sh --update`, compare the diff with GitHub's announcement and merge it by
pull request. A tag pushed before that does not publish: once the pins are on `main`, tag the next patch version. A
key GitHub adds is only a warning until it is pinned.

The Scoop manifest's `autoupdate` reads the archive's line of the release's `SHA256SUMS` instead of hashing the
download: it catches a corrupt or swapped download, but is no stronger than the release, since Scoop does not check
the signature of `SHA256SUMS`.

## Verifying

Signatures use a key pair (no keyless signing: it would write this repository's name in the public Rekor log), so the
transparency log is not consulted.

**cosign public key** (the private half is the organisation secret `COSIGN_PRIVATE_KEY`):

```
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENbn5GmwW348HxCtTPQbNfcvHmSZG
G+IBNKubzv51TIua776a3875eoe5isQYKYmVwe5yTUU0v2k8Pu7WXQWoBg==
-----END PUBLIC KEY-----
```

Save it as `cosign.pub`, then:

```sh
cosign verify-blob --key cosign.pub --insecure-ignore-tlog \
  --signature ostrom-1.0.0-linux-x64.tar.gz.sig ostrom-1.0.0-linux-x64.tar.gz
cosign verify-blob --key cosign.pub --insecure-ignore-tlog --signature SHA256SUMS.sig SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
cosign verify --key cosign.pub --insecure-ignore-tlog docker.io/ostromhq/ostrom-runner:1.0.0
```

(`install.sh` and `install.ps1` check the signature of `SHA256SUMS` against the pinned cosign key, with `cosign` when it
is installed, else with `openssl` (install.sh) or .NET (install.ps1), then the SHA-256 of the archive. Without either
tool, install.sh installs nothing: no variable skips the signature check (the former `OSTROM_SKIP_COSIGN` is gone, as TeamCode's installer has none). A dry run of the release signs with throwaway keys, never with
the release keys.)

## The update manifest, for `ostrom update`

`latest.json` is served at `<releases>/latest/download/latest.json` (on GitHub: the `latest` release, which is the
newest non-draft, non-pre-release one):

```json
{
  "version": "1.0.0",
  "floor": "1.0.0",
  "date": "2026-10-30T10:00:00Z",
  "assets": {
    "linux-x64":   { "url": "https://github.com/ostrom-hq/ostrom-cli/releases/download/1.0.0/ostrom-1.0.0-linux-x64.tar.gz",   "sha256": "<hex>", "size": 1234567 },
    "linux-arm64": { "url": "...", "sha256": "<hex>", "size": 0 },
    "macos-arm64": { "url": "...", "sha256": "<hex>", "size": 0 },
    "windows-x64": { "url": "https://.../ostrom-1.0.0-windows-x64.zip", "sha256": "<hex>", "size": 0 }
  }
}
```

`sha256` is lowercase hex, `size` is in bytes. The archive holds `ostrom` (`ostrom.exe` on Windows) at its root.

`floor` is the lowest version `ostrom update --version` and `ostrom update --rollback` may install (security audit
S2-39): signed with the manifest, so a downgrade to a version with a known hole cannot be slipped in. It comes from
`release-floor.txt` at the root of the repository (or `RELEASE_FLOOR`), `MAJOR.MINOR.PATCH`, never above the version
released (`latest-json.sh` refuses it). **Raise it in the release that fixes a security hole**, to that release's
version. The CLI reads the floor of the latest release, never the old release's own manifest, and refuses any move
back when the latest release publishes none. Each binary also carries the floor of `release-floor.txt` at its build
(`BuildInfo.FLOOR`) and never installs below it: an old `latest.json` served again, still signed but with a lower
floor, cannot lower it. A signed expiry date of the manifest, to refuse a replayed one outright, is not there yet.

`latest.json.sig` is the **base64 (one line) of the raw 64-byte Ed25519 signature of the exact bytes of `latest.json`**
(no hashing first, no canonicalisation: verify the bytes as downloaded). `ostrom update` must:

1. download `latest.json` and `latest.json.sig`;
2. verify the signature with the public key below, and refuse to go on if it fails;
3. compare `version` with its own (never move to an older or equal version unless asked, and never below the latest
   release's `floor`);
4. download `assets[<its target>].url`, check `size` and `sha256`, unpack;
   (a `github.com/<repo>/releases/download/…` address is fetched through GitHub's REST asset endpoint, `api.github.com/repos/<repo>/releases/assets/<id>`, the id read from the release's JSON: the public redirect carries a header line over 8 KB that the Linux and Windows engines refuse; the bytes are still verified as below; TeamCode 29adcf48)
5. run the unpacked binary with `--ostrom-installation-probe <version> <apiVersion>` (the API version of the server
   the device is signed in to, else its own) and refuse it unless it exits 0 ([COMPATIBILITY.md](COMPATIBILITY.md));
6. replace the binary atomically.

**Ed25519 public key to embed in the CLI** (SPKI DER, base64; the private half is the organisation secret
`RELEASE_SIGNING_KEY`, PEM PKCS#8):

```
MCowBQYDK2VwAyEAgWVuEFjR9qPX6DfIJ6Bhs+jT7j00KiwFY6bNwP0fexA=
```

The last 32 bytes of the decoded DER are the raw public key (the first 12 bytes are the fixed SPKI prefix
`302a300506032b6570032100`). Check by hand:

```sh
{ echo '-----BEGIN PUBLIC KEY-----'; echo 'MCowBQYDK2VwAyEAgWVuEFjR9qPX6DfIJ6Bhs+jT7j00KiwFY6bNwP0fexA='; echo '-----END PUBLIC KEY-----'; } > release.pub
base64 -d < latest.json.sig > latest.sig       # base64 -D on macOS
openssl pkeyutl -verify -pubin -inkey release.pub -rawin -in latest.json -sigfile latest.sig
```

## Trust roots, `ostrom verify-release` and rotating the key

The Ed25519 roots that may sign `latest.json` are the `*.pem` files of `release/trust/` (one to eight, each an Ed25519
SubjectPublicKeyInfo): the build compiles them into the binary (`ShippedReleaseKeys`), and `ostrom update` accepts a
manifest signed by any of them. A root is told by its **key id**, the SHA-256 of its SubjectPublicKeyInfo (the same id
as TeamCode's `release/trust` keys); `ostrom update --check`, `ostrom update --check --json` and `ostrom verify-release`
name the root that signed. A manifest cannot bring a root, and neither can the server: a fork replaces the files and
rebuilds (and signs with its own key).

`ostrom verify-release` checks a release downloaded by hand, offline: no login, no server, nothing executed.

```sh
ostrom verify-release --manifest latest.json --signature latest.json.sig
ostrom verify-release --manifest latest.json --signature latest.json.sig \
  --archive ostrom-1.0.0-linux-x64.tar.gz --target linux-x64
```

It checks the signature over the exact bytes before reading the manifest, then (with `--archive`) the archive's size
and SHA-256 against the signed entry of that target. Exit codes: 0 verified, 1 a manifest that does not read, 64 bad
usage, 66 a file or a target that is not there, 78 a signature or a checksum that does not match.

**Rotating the key.** A binary only knows the roots it was built with, so the next key is shipped before it is used:

1. **Bridge release N**: add the new key's `.pem` to `release/trust/` and cut a release signed with the *current* key.
   Its binaries trust both. The release job refuses to publish when the manifest verifies with none of the roots.
2. Wait until the clients you care about have moved to N or later (`ostrom update`, or the installers: they pin the
   cosign key apart, see below). A client older than N cannot verify anything signed with the new key: it must update
   to N first, or reinstall.
3. **Release N+1**: change the organisation secret `RELEASE_SIGNING_KEY` to the new key, cut the release signed with
   it. Binaries of N and N+1 verify it.
4. **Retire the old root** in a later release (delete its `.pem`): a manifest signed with the old key is then refused.

A compromised key still needs an independently authenticated recovery channel (a reinstall from a source you trust):
no remote revocation of a root already installed is claimed, as in TeamCode. The cosign key that signs the archives and
`SHA256SUMS` (pinned in the installers) rotates the same way: the installers of the bridge release pin the new key.

## Update notices and their opt-out

`ostrom update` is manual; there is a once-a-day notice on stderr after a command (`! ostrom X is available`), refreshed
by a detached background lookup. Turn it off for this device with `ostrom update --notifications off`
(`on`, `status`), or in one process, a CI or a locked environment, with any non-empty `OSTROM_DISABLE_AUTOUPDATE`. The
explicit `ostrom update --check` still works. `ostrom update --check --json` prints one line, from the signed manifest
only: `{"currentVersion":"1.0.0","latestVersion":"1.1.0","updateAvailable":true,"keyId":"<id>"}`.

## Installing

```sh
curl -fsSL https://ostrom.run/install.sh | sh           # Linux, macOS (Apple silicon)
irm https://ostrom.run/install.ps1 | iex                # Windows (PowerShell)
brew install ostrom-hq/tap/ostrom                       # Homebrew
scoop bucket add ostrom https://github.com/ostrom-hq/scoop-bucket; scoop install ostrom
```

On macOS, `install.sh` downloads with `curl`, which sets no quarantine attribute: Gatekeeper checks nothing at the
first start. An archive downloaded with a browser is quarantined, and Gatekeeper checks it at its first start: until
the release is signed with a Developer ID and notarised (see "Signing the binaries themselves"), it warns. CI does not
measure that check: the runner's shell starts even a quarantined copy that `spctl --assess` rejects without a hold
(cli#111).
The first start of a new binary costs its signature check, about 25 ms on a CI runner (39 ms, then 15 ms; budget of
100 ms, `e2e/perf.py --max-installed-ms`). The ~550 ms that CI measures with the file cache emptied (`purge`) come
from the system libraries every program loads (Foundation, Security), which a running Mac keeps in memory as long as
they are in use; when a developer's Mac has dropped them, after a restart for instance, has not been measured.

Installer variables: `OSTROM_VERSION` (default: the latest, through `latest.json`), `OSTROM_INSTALL_ROOT` (the
managed installation's folder, `--root` below), `OSTROM_INSTALL_DIR` (install the single binary into this folder
instead, as the installers did before `ostrom install`; never needs root), `OSTROM_DOWNLOAD_BASE` (default
`https://github.com/ostrom-hq/ostrom-cli/releases`), `OSTROM_NO_MODIFY_PATH=1` (`--no-path` below; for the single
binary, no line in a shell profile or the user PATH).

Once the archive is checked, the installers hand over to it: they download the release's `latest.json` and
`latest.json.sig` next to the archive and run the unpacked binary as
`ostrom install --bundle <that folder> --version <version>`, which checks the Ed25519 signature again and makes the
managed installation below; it never edits a shell profile. With `OSTROM_INSTALL_DIR` (not together with
`OSTROM_INSTALL_ROOT`), the single binary is put in that folder instead. A single binary already in `~/.ostrom/bin`
is left in place, and the installer says to remove it.

Both installers check the signature of `SHA256SUMS` before the archive's checksum. `install.sh` does it with `cosign`
or, without it, `openssl`. With neither, it installs nothing: the checksum alone comes from the same place as the
archive. Nothing skips the signature check; install cosign or openssl instead. `install.ps1` always has .NET to check it.

### The managed installation (`ostrom install`)

`ostrom install` installs a signed release for the current user, with the same checks as `ostrom update` (the
`latest.json` signature, the floor, the archive's SHA-256, the probe of the new binary, never inside an agent's
session), into a private folder: `~/.local/share/ostrom` on Linux (`$XDG_DATA_HOME/ostrom`),
`~/Library/Application Support/Ostrom` on macOS, `%LOCALAPPDATA%\Ostrom` on Windows, or `--root`. Its layout follows
TeamCode's:

- `bin/ostrom`: the launcher, copied once from the first version installed and never changed; it runs the selected
  version in its place. `bin/launcher.json` attests it.
- `versions/<SHA-256 of latest.json>/`: one folder per release, with its binary, the exact `latest.json` and
  `latest.json.sig`, and `installed.json` (the binary's SHA-256 and size, written once the archive was checked).
- `current.json`: the selected version and the previous one, switched atomically; `transaction.json` while a switch is
  under way, so that an interrupted one is put back by the next launch. Every launch checks the selected binary
  against its attestation before running it.
- `launch-cache.json`: the file identity of the selected binary (device and inode, or volume serial and file index on
  Windows; size; modification and change times) recorded after a launch checked its SHA-256 in full. A launch whose
  binary still has exactly that identity skips the SHA-256 (hashing the whole binary took about 400 ms on every hook);
  any difference, or a record that is missing or does not read, checks in full again. Deleting the file is harmless.
- `writer.lock` (one installer at a time) and `installation.lock` (a switch or a recovery).

`ostrom update` and `ostrom update --rollback` run from such an installation add a version folder and switch the
selection; the versions that are neither selected nor the previous one are removed. `--bundle <folder>` installs
offline from a folder holding `latest.json`, `latest.json.sig` and the archive. Unless `--no-path`, the launcher is
linked as `~/.local/bin/ostrom` (macOS, Linux; a name held by another file is left alone) or its folder appended to
the user PATH (Windows). A single binary installed by the installers keeps updating in place, with
`ostrom.previous` for `--rollback`.

`ostrom install` and `ostrom update` end with the local service step (`ostrom service`): the service run by the
installation's launcher is installed and started at login, or its login startup checked and repaired, and one
`Local Ostrom service: ...` line says what happened (on the error stream when it failed, without changing the exit code
or undoing the installation). `--no-service` or a non-empty `OSTROM_NO_SERVICE` skips it, so does
`ostrom service automatic off`, a registration made with `--at-boot`, a service run by another executable, a binary that
is not a managed installation, and a cloud session's runner. It never asks for elevation.

### Automatic updates (`ostrom update --automatic`)

A managed installation can update itself every hour, once you opt in with `ostrom update --automatic on` (`off`,
`status`, `uninstall`; `--root` names another installation). Nothing else ever registers the job. It is a user job of
the system's own scheduler, separate from any session: a LaunchAgent in `~/Library/LaunchAgents` on macOS, a systemd
user timer and service in `$XDG_CONFIG_HOME/systemd/user` (`~/.config/systemd/user`) on Linux, a Task Scheduler task
running as the user who registered it on Windows. It runs the stable launcher `bin/ostrom` of the installation, and
ostrom manages only the exact definitions it recorded: one changed by hand is refused, never overwritten.

- `automatic-update.json`, in the installation's folder, holds paths and identity only (the CLI folder, the launcher,
  the job's definition, the system, the user id), never a token or a login. `on` records it disabled before
  registering the job, so a setup that fails leaves the updates off; `off` never stops an attempt under way.
- Each attempt goes through the same checks as a managed `ostrom update`: the signature, the floors, the archive's
  checksum, the probe of the new binary, never inside an agent's session. It installs the latest release only when it
  is newer and not a prerelease (a version with a `-`), within 15 minutes, one attempt at a time.
- `automatic-update-result.json` records the last attempt: `STARTED`, `NO_UPDATE`, `UPDATED`, `DEFERRED` (another
  install, update or attempt is running, or an agent's session; `automatic_worker_busy` when another attempt holds the
  worker's lock) or `FAILED`, with a diagnostic code and never a remote body. `ostrom update --automatic status`
  shows it.
- Never for root. With any non-empty `OSTROM_DISABLE_AUTOUPDATE` in the job's own environment, an attempt does
  nothing, and the status says so when it is set where you run it; a variable exported in a shell does not reach the
  job: turn it off with `ostrom update --automatic off`.

## Signing the binaries themselves

macOS (Developer ID + notarisation) and Windows (Authenticode) run in the `build-macos` and `package` jobs only when
their secrets exist, and are skipped otherwise:

- macOS: `APPLE_CERTIFICATE_P12` (base64 of the .p12), `APPLE_CERTIFICATE_PASSWORD`, `APPLE_SIGNING_IDENTITY`; to
  notarise as well: `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` (app-specific password).
- Windows: `WINDOWS_CERTIFICATE_PFX` (base64 of the .pfx), `WINDOWS_CERTIFICATE_PASSWORD`.

Until they are set, macOS Gatekeeper and Windows SmartScreen warn on a downloaded binary (a binary installed by
`install.sh`/`curl` carries no quarantine flag and runs without a warning).

## The runner image

`docker/runner/Dockerfile`: `node:24-bookworm-slim`, user `ostrom` (uid 10001), git, openssh-client, ca-certificates,
libcurl4, ripgrep, the `ostrom` binary of the release, Claude Code and Codex at pinned versions (build arguments
`CLAUDE_CODE_VERSION`, `CODEX_VERSION`), `ENTRYPOINT ["ostrom"]`. `docker run --rm <image> version` prints the CLI version;
`--entrypoint claude` or `--entrypoint codex` reaches the agents.

## winget

The release carries the three manifest files of `OstromHQ.Ostrom`. After the go, submit them:
`wingetcreate submit` or a pull request to `microsoft/winget-pkgs` under `manifests/o/OstromHQ/Ostrom/<version>/`.
