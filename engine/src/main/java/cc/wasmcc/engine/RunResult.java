package cc.wasmcc.engine;

/**
 * Outcome of a mode-B command run (run-to-completion): the WASI exit code plus
 * captured stdout/stderr. Output files the module wrote land on the mounted host
 * directory (the CC computer's disk), read back by the caller via normal fs.
 */
public record RunResult(int exit, String stdout, String stderr) {}
