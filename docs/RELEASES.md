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
at once and the Homebrew tap and Scoop bucket are updated (`taps` job). The packages under `ghcr.io/ostrom-hq` are
made public in the organisation's package settings.

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

Also published: the image `ghcr.io/ostrom-hq/ostrom-runner:<version>` and `:latest` (linux/amd64 and linux/arm64,
signed by digest, with an SBOM and max-mode provenance attached by buildx), the Homebrew formula
(`ostrom-hq/homebrew-tap`, `Formula/ostrom.rb`) and the Scoop manifest (`ostrom-hq/scoop-bucket`, `bucket/ostrom.json`).

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
cosign verify --key cosign.pub --insecure-ignore-tlog ghcr.io/ostrom-hq/ostrom-runner:1.0.0
```

(`install.sh` and `install.ps1` check the signature of `SHA256SUMS` against the pinned cosign key, with `cosign` when it
is installed, else with `openssl` (install.sh) or .NET (install.ps1), then the SHA-256 of the archive. Without either
tool, install.sh says the signature was not checked. A dry run of the release signs with throwaway keys, never with
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
4. download `assets[<its target>].url`, check `size` and `sha256`, unpack, replace the binary atomically.

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

## Installing

```sh
curl -fsSL https://ostrom.run/install.sh | sh           # Linux, macOS (Apple silicon)
irm https://ostrom.run/install.ps1 | iex                # Windows (PowerShell)
brew install ostrom-hq/tap/ostrom                       # Homebrew
scoop bucket add ostrom https://github.com/ostrom-hq/scoop-bucket; scoop install ostrom
```

Installer variables: `OSTROM_VERSION` (default: the latest, through `latest.json`), `OSTROM_INSTALL_DIR` (default
`~/.ostrom/bin`; never needs root), `OSTROM_DOWNLOAD_BASE` (default `https://github.com/ostrom-hq/ostrom-cli/releases`),
`OSTROM_NO_MODIFY_PATH=1`, `OSTROM_SKIP_COSIGN=1`.

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
