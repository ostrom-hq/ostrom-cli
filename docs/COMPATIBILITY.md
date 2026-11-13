# Server compatibility

An Ostrom server says what it speaks and serves in its capabilities (`GET /api/v1/capabilities`, operation
`capabilities` of ostrom-core's contract, read with any signed-in credential): `apiVersion`, the version of the `/api/v1` contract it speaks, which changes only
on a breaking change; `serverVersion`, its release; and `features`, the keys of the optional features it serves and
has configured (`runners.cloud.v1`, `import.teamcode.v1`, `billing.v1`…, `ServerFeatures` in ostrom-core). This
ostrom speaks the API versions of the ostrom-core it is built on (`ServerFeatures.SUPPORTED_API_VERSIONS`: v1 today).

The capabilities are read only by the commands that need them, once per server for the process, over the connection
the command already uses; nothing is kept on disk (a copy would go on saying what a server spoke before its upgrade).
`ostrom --version`, the hooks and the other commands never read them.

## `ostrom login`

Once the device flow has given a token, and before anything is kept, `ostrom login` (and `ostrom login --admin`)
reads the capabilities with that token:

| The server | What `ostrom login` does |
| --- | --- |
| speaks an API this ostrom speaks | keeps the token and the server, as before |
| speaks another API | keeps nothing, revokes the new token, exits 69 naming both API versions and the side to update (`ostrom update` when the server is newer, the server when it is older) |
| has no capabilities (404) | keeps nothing, revokes the new token, exits 69: the server is too old for this ostrom, ask your admin to update it |
| fails to answer them (5xx, unreachable) | keeps nothing, revokes the new token, exits 69: run `ostrom login` again |

A server without capabilities is a development build of ostrom-server from before it served them (ostrom-server#364,
November 2026); no release lacks them. The app and the web console refuse it the same way.

## `ostrom doctor`

Signed in to a server that answers its health route, the doctor reads its capabilities with the device's token and
says which API it speaks: ✓ when this ostrom speaks it; ✗ (an error, exit 1) when it does not, with the side to
update, or when it has no capabilities (too old); ! for one that refused the token (run `ostrom login`), or one that
failed to answer. The doctor's sign-in line now says who is signed in. The token is never sent to a server in clear (`http://`
other than this machine).

## `ostrom update`

`ostrom update` and `ostrom update --rollback`, once every other guard has passed (the signature, the floor, no
agent's session), read the API version of the server this device is signed in to, then run the binary to put in
place (the new one, unpacked and verified; the kept one, for a rollback) with the hidden first argument
`--ostrom-installation-probe <version> <apiVersion>`. A binary exits 0 to it only when it is exactly that version and
speaks that API; anything else (another version, another API, a binary that does not start on this machine) refuses
the update with exit 77 and changes nothing.

Not signed in, or a server that cannot say (unreachable, without capabilities, failing): the binary is checked against
the API this ostrom speaks, with a warning, and the update goes on. Updating ostrom without the server at hand (on a
plane, before the server is up, to fix ostrom itself) is legitimate, and the binary is still proven to run here and to
be the version signed. TeamCode refuses instead (`release_server_unavailable`), but its update also restarts a local
service that needs its server.

## The commands of an optional feature

They read the capabilities first and stop with exit 69 on a server that does not serve the feature, or speaks
another API:

| Command | Feature | Said when the server does not serve it |
| --- | --- | --- |
| `ostrom run claude --cloud` (any agent), `ostrom attach` | `runners.cloud.v1` | This Ostrom server runs no cloud session: start the agent here, without --cloud. |
| `ostrom github` (`link`, `links`, `unlink`) | `github.link.v1` | This Ostrom server does not link GitHub accounts: ask your admin to update it. |
| `ostrom cloud github` | `runners.cloud.github.v1` | This Ostrom server does not share a GitHub account with cloud sessions: ask your admin to update it. |
| `ostrom import teamcode` | `import.teamcode.v1` | This Ostrom server does not import TeamCode teams: ask your admin to update it. |

A server without capabilities is too old and stops the command too. One that fails to answer them (401, 5xx) lets the
command go on: the feature's own route answers, in the command's words. A server that cannot be reached stops it at
once (exit 69).

The CLI has no command for the other optional features yet (billing, Slack, push notifications); each gets the
same check when it comes.

## Former command paths

The command tree follows TeamCode's (`teamcode run claude -- <args>`). A
command that moves keeps its former path for one release, hidden from `ostrom --help` and from completion: it runs as
before and first says on stderr, in one line, that it goes away and which path to use. Scripts, shell aliases and
hooks written for the former path go on working until the release after.

| Former path | Path | Since |
| --- | --- | --- |
| `ostrom <agent> [options] [agent args]` (`claude`, `codex`, `opencode`, `gemini`, `grok`, `hermes`, `pi`) | `ostrom run <agent> [options] -- [agent args]` | this release |

`ostrom run <agent>` passes everything after `--` to the agent untouched; before `--`, an option that is not Ostrom's
(`-p`, `--resume`) is a usage error (exit 64) rather than something passed on, as TeamCode does. `ostrom init` writes
the shell aliases with the new path (`alias claude='ostrom run claude --'`): run it again to replace the former ones.
