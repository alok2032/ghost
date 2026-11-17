import vm from "node:vm";
import type { CodeRunRequest } from "../validation/schemas.js";

export interface SandboxExecutionResult {
  ok: boolean;
  language: CodeRunRequest["language"];
  resultText: string;
  stdout: string;
  durationMs: number;
  truncated: boolean;
  error?: string;
}

const MAX_STDOUT_CHARS = 16_384;

const FORBIDDEN_STATIC_PATTERNS: Array<{ pattern: RegExp; reason: string }> = [
  { pattern: /\bprocess\b/, reason: "Access to Node 'process' object is prohibited in sandbox" },
  { pattern: /\brequire\s*\(/, reason: "Module loading via require() is prohibited in sandbox" },
  { pattern: /\bimport\s*\(/, reason: "Dynamic import() is prohibited in sandbox" },
  { pattern: /\bchild_process\b/, reason: "Subprocess APIs are prohibited in sandbox" },
  { pattern: /\bglobalThis\b|\bglobal\b/, reason: "Global escape identifiers are prohibited in sandbox" },
  { pattern: /\bFunction\s*\(|\beval\s*\(/, reason: "Dynamic code generation is prohibited in sandbox" },
  { pattern: /\b__proto__\b|\bconstructor\s*\.\s*constructor\b/, reason: "Prototype chain escape patterns are prohibited in sandbox" },
];

export function executeSandboxedCode(req: CodeRunRequest): SandboxExecutionResult {
  const started = Date.now();

  for (const rule of FORBIDDEN_STATIC_PATTERNS) {
    if (rule.pattern.test(req.code)) {
      return {
        ok: false,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        error: rule.reason,
      };
    }
  }

  let parsedInput: unknown = null;
  if (req.inputJson && req.inputJson.trim().length > 0) {
    try {
      parsedInput = JSON.parse(req.inputJson);
    } catch {
      return {
        ok: false,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        error: "Invalid inputJson payload",
      };
    }
  }

  const outputLines: string[] = [];
  let stdoutChars = 0;
  let truncated = false;

  const appendLog = (...args: unknown[]) => {
    const line = args
      .map((item) => {
        if (typeof item === "string") return item;
        try {
          return JSON.stringify(item);
        } catch {
          return String(item);
        }
      })
      .join(" ");
    if (stdoutChars + line.length + 1 <= MAX_STDOUT_CHARS) {
      outputLines.push(line);
      stdoutChars += line.length + 1;
    } else {
      truncated = true;
    }
  };

  // Fresh null-prototype context with zero Node host capabilities (no fs, net, fetch, process, timers)
  const sandbox = Object.create(null) as Record<string, unknown>;
  sandbox.Math = Math;
  sandbox.JSON = JSON;
  sandbox.Array = Array;
  sandbox.Object = Object;
  sandbox.String = String;
  sandbox.Number = Number;
  sandbox.Boolean = Boolean;
  sandbox.Date = Date;
  sandbox.RegExp = RegExp;
  sandbox.Map = Map;
  sandbox.Set = Set;
  sandbox.parseInt = parseInt;
  sandbox.parseFloat = parseFloat;
  sandbox.isNaN = isNaN;
  sandbox.isFinite = isFinite;
  sandbox.input = parsedInput;
  sandbox.console = Object.freeze({
    log: appendLog,
    info: appendLog,
    warn: appendLog,
    error: appendLog,
  });

  const wrappedSource =
    req.language === "math"
      ? `"use strict"; (${req.code.trim()})`
      : `"use strict"; (() => {\n${req.code}\n})()`;

  try {
    const context = vm.createContext(sandbox, {
      codeGeneration: { strings: false, wasm: false },
    });
    const script = new vm.Script(wrappedSource, { filename: "ayra_sandbox_snippet.js" });
    const value = script.runInContext(context, {
      timeout: req.timeoutMs,
      breakOnSigint: true,
    });

    let resultText = "";
    if (value !== undefined) {
      if (typeof value === "string") {
        resultText = value;
      } else {
        try {
          resultText = JSON.stringify(value, null, 2) ?? String(value);
        } catch {
          resultText = String(value);
        }
      }
    }
    if (resultText.length > MAX_STDOUT_CHARS) {
      resultText = resultText.slice(0, MAX_STDOUT_CHARS);
      truncated = true;
    }

    return {
      ok: true,
      language: req.language,
      resultText,
      stdout: outputLines.join("\n"),
      durationMs: Date.now() - started,
      truncated,
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Sandbox execution failed";
    return {
      ok: false,
      language: req.language,
      resultText: "",
      stdout: outputLines.join("\n"),
      durationMs: Date.now() - started,
      truncated,
      error: message.slice(0, 300),
    };
  }
}
