// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

/**
 * The plugins Ostrom gives the agents that load JavaScript rather than hook commands (prototype `bridge/plugin`). They
 * call this binary (`$OSTROM_BIN hook gate|session`) with a Claude-shaped hook event, as Claude Code and Codex do.
 */
internal object EmbeddedPlugins {
    /** Where the Pi extension takes its MCP servers: `{"<label>": [<ostrom mcp arguments>]}`. */
    const val MCP_SERVERS = "__OSTROM_MCP_SERVERS__"

    /**
     * Ostrom for OpenCode (plugin API of `@opencode-ai/plugin` 1.18), loaded for one session
     * (`OPENCODE_CONFIG_DIR`): `tool.execute.before` is the PreToolUse gate (exit 2 throws: OpenCode refuses the tool
     * call), `tool.execute.after` the PostToolUse one, the first event of a session SessionStart, `session.deleted`
     * SessionEnd.
     */
    val OPENCODE = """
        |// Ostrom for OpenCode: the gate and session hooks of `ostrom`, fed a Claude-shaped hook event.
        |import { spawn } from 'node:child_process';
        |
        |// OpenCode's built-in tools by the Claude names the gates know; any other name is an MCP server's tool
        |const TOOL = {
        |  bash: 'Bash', write: 'Write', edit: 'Edit', patch: 'Edit', apply_patch: 'Edit', multiedit: 'MultiEdit',
        |  read: 'Read', grep: 'Grep', glob: 'Glob', list: 'LS', webfetch: 'WebFetch', task: 'Task', todowrite: 'TodoWrite',
        |};
        |
        |function run(kind, event, timeoutMs) {
        |  return new Promise((resolve) => {
        |    const child = spawn(process.env.OSTROM_BIN, ['hook', kind], { env: process.env, stdio: ['pipe', 'pipe', 'pipe'] });
        |    let out = '', err = '';
        |    child.stdout.on('data', (d) => (out += d));
        |    child.stderr.on('data', (d) => (err += d));
        |    const t = setTimeout(() => child.kill('SIGTERM'), timeoutMs);
        |    child.on('error', (e) => { clearTimeout(t); resolve({ code: 1, out: '', err: String(e) }); });
        |    child.on('exit', (code) => { clearTimeout(t); resolve({ code: code ?? 1, out, err }); });
        |    child.stdin.end(JSON.stringify(event));
        |  });
        |}
        |
        |// the files a patch touches (`*** Add|Update|Delete File: <path>`, and `*** Move to: <path>`)
        |function patchedFiles(text = '') {
        |  return [...String(text).matchAll(/^\*\*\* (?:(?:Add|Update|Delete) File|Move to): (.+)$/gm)].map((m) => m[1].trim());
        |}
        |
        |function toolInput(tool, args = {}) {
        |  if (tool === 'bash') return { command: args.command ?? '' };
        |  if (tool === 'apply_patch' || tool === 'patch') {
        |    const files = patchedFiles(args.patchText ?? args.patch);
        |    return { file_path: files[0] ?? '', file_paths: files, patch: args.patchText ?? args.patch };
        |  }
        |  if (args.filePath) return { file_path: args.filePath, content: args.content, new_string: args.newString };
        |  return args;
        |}
        |
        |const started = new Set();
        |// tool.execute.after does not carry the arguments: those of tool.execute.before, by call
        |const argsOf = new Map();
        |
        |const server = async ({ directory, worktree }) => {
        |  // the folder OpenCode runs in; its worktree is the repository's root, or "/" outside a repository
        |  const cwd = directory || worktree || process.cwd();
        |  const startOnce = async (sessionID) => {
        |    if (!sessionID || started.has(sessionID)) return;
        |    started.add(sessionID);
        |    await run('session', { hook_event_name: 'SessionStart', session_id: sessionID, cwd, source: 'opencode' }, 20000);
        |  };
        |  const end = async (sessionID) => {
        |    if (!sessionID || !started.has(sessionID)) return;
        |    started.delete(sessionID);
        |    await run('session', { hook_event_name: 'SessionEnd', session_id: sessionID, cwd, reason: 'opencode' }, 30000);
        |  };
        |  const event = (name, input, output) => ({
        |    hook_event_name: name, session_id: input.sessionID, cwd,
        |    tool_name: TOOL[input.tool] ?? (input.tool.includes('_') ? 'mcp__' + input.tool : input.tool),
        |    tool_input: toolInput(input.tool, output?.args ?? input.args),
        |  });
        |  return {
        |    event: async ({ event: ev }) => {
        |      const sid = ev?.properties?.info?.id ?? ev?.properties?.sessionID;
        |      if (ev?.type === 'session.created') await startOnce(sid);
        |      if (ev?.type === 'session.deleted') await end(sid);
        |    },
        |    'tool.execute.before': async (input, output) => {
        |      await startOnce(input.sessionID);
        |      argsOf.set(input.callID, output?.args);
        |      const r = await run('gate', event('PreToolUse', input, output), 610000);
        |      // fail closed: a refusal (2), but also a crash, a spawn error or a hook killed at its limit
        |      if (r.code === 2) throw new Error(r.err.trim() || 'Ostrom: this action waits for a human yes.');
        |      if (r.code !== 0) throw new Error('Ostrom gate failed (' + r.err.trim().slice(0, 300) + '); the action was not run.');
        |    },
        |    'tool.execute.after': async (input) => {
        |      const args = argsOf.get(input.callID) ?? input.args;
        |      argsOf.delete(input.callID);
        |      await run('gate', event('PostToolUse', input, { args }), 15000);
        |    },
        |  };
        |};
        |
        |// the session also ends with the process (opencode run): `ostrom opencode` ends it anyway
        |export default { id: 'ostrom', server, setup: async () => {} };
        |
    """.trimMargin()

    /**
     * Ostrom for Pi (`pi -e <file>`, for one session): `tool_call` is the PreToolUse gate (a refusal is
     * `{ block: true, reason }`, and Pi blocks the tool when the handler throws: fail-closed), `tool_result` the
     * PostToolUse one, `session_start` and `session_shutdown` the session hooks; the session context goes into the
     * system prompt, and the relayed MCP servers are registered from [MCP_SERVERS].
     */
    val PI = """
        |// Ostrom for Pi: the gate and session hooks of `ostrom`, fed a Claude-shaped hook event.
        |import { spawn } from 'node:child_process';
        |
        |const SERVERS: Record<string, string[]> = $MCP_SERVERS;
        |
        |function run(kind: string, event: unknown): Promise<{ code: number; out: string; err: string }> {
        |  return new Promise((resolve) => {
        |    const p = spawn(process.env.OSTROM_BIN as string, ['hook', kind], {
        |      env: { ...process.env, OSTROM_HOOK_DIALECT: 'claude' }, stdio: ['pipe', 'pipe', 'pipe'],
        |    });
        |    let out = '', err = '';
        |    p.stdout.on('data', (d) => { out += d; });
        |    p.stderr.on('data', (d) => { err += d; });
        |    p.on('close', (code) => resolve({ code: code ?? 1, out, err }));
        |    p.on('error', (e) => resolve({ code: 1, out: '', err: String(e) }));
        |    p.stdin.end(JSON.stringify(event));
        |  });
        |}
        |
        |function lastContext(out: string): string {
        |  try { return JSON.parse(out.trim().split('\n').pop() || '{}').hookSpecificOutput?.additionalContext ?? ''; }
        |  catch { return ''; }
        |}
        |
        |export default function ostrom(pi: any) {
        |  let sessionId = '';
        |  let context = '';
        |  let cwd = process.cwd();
        |  const notes = new Map<string, string>();
        |
        |  for (const [name, args] of Object.entries(SERVERS)) {
        |    pi.registerMcpServer(name, { command: process.env.OSTROM_BIN as string, args, env: { ...process.env } });
        |  }
        |
        |  pi.on('session_start', async (_e: unknown, ctx: any) => {
        |    sessionId = ctx.sessionManager?.getSessionId?.() ?? 'pi-' + Date.now();
        |    cwd = ctx.cwd ?? cwd;
        |    const r = await run('session', { hook_event_name: 'SessionStart', session_id: sessionId, cwd, source: 'startup' });
        |    context = lastContext(r.out);
        |  });
        |
        |  pi.on('before_agent_start', async (event: any) => {
        |    if (!context) return;
        |    return { systemPrompt: event.systemPrompt + '\n\n' + context };
        |  });
        |
        |  pi.on('tool_call', async (event: any) => {
        |    const r = await run('gate', { hook_event_name: 'PreToolUse', session_id: sessionId, cwd, tool_name: event.toolName, tool_input: event.input });
        |    if (r.code === 2) return { block: true, reason: r.err.trim() || 'Blocked by Ostrom.' };
        |    if (r.code !== 0) return { block: true, reason: 'Ostrom gate failed (' + r.err.trim().slice(0, 300) + '); the action was not run.' };
        |    const note = lastContext(r.out);
        |    if (note) notes.set(event.toolCallId, note);
        |  });
        |
        |  pi.on('tool_result', async (event: any) => {
        |    await run('gate', { hook_event_name: 'PostToolUse', session_id: sessionId, cwd, tool_name: event.toolName, tool_input: event.input });
        |    const note = notes.get(event.toolCallId);
        |    if (note) { notes.delete(event.toolCallId); return { content: [...(event.content ?? []), { type: 'text', text: '\n[' + note + ']' }] }; }
        |  });
        |
        |  pi.on('session_shutdown', async () => {
        |    if (sessionId) await run('session', { hook_event_name: 'SessionEnd', session_id: sessionId, cwd, reason: 'pi session_shutdown' });
        |  });
        |}
        |
    """.trimMargin()
}
