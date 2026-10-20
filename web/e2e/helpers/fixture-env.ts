export const FIXTURE_KEYS = ['E2E_FIXTURE_BUYER_EMAIL', 'E2E_FIXTURE_BUYER_PASSWORD',
  'E2E_FIXTURE_SELLER_EMAIL', 'E2E_FIXTURE_SELLER_PASSWORD', 'E2E_FIXTURE_HW_PAID_ORDER_ID',
  'E2E_FIXTURE_SW_DELIVERED_ORDER_ID'] as const;
export type FixtureKey = typeof FIXTURE_KEYS[number];
export type FixtureEnv = Partial<Record<FixtureKey, string>>;
import { readFileSync, renameSync, writeFileSync, rmSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

const envPath = resolve(__dirname, '../.env.e2e');
const invalid = () => new Error('Invalid fixture environment data');

function allowed(key: string): key is FixtureKey {
  return (FIXTURE_KEYS as readonly string[]).includes(key);
}

function assignment(line: string): RegExpMatchArray | null {
  return line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/);
}

export function parseFixtureEnv(text: string): FixtureEnv {
  const result: FixtureEnv = {};
  for (const line of text.split(/\r\n|\n|\r/)) {
    const match = assignment(line);
    if (!match || !allowed(match[1])) continue;
    const key = match[1];
    if (key in result) throw invalid();
    let value = match[2].trim();
    try {
      if (value.startsWith('"')) value = JSON.parse(value);
      else if (value.startsWith("'")) {
        if (!value.endsWith("'")) throw invalid();
        value = value.slice(1, -1);
      }
    } catch { throw invalid(); }
    if (typeof value !== 'string' || /[\r\n\0]/.test(value)) throw invalid();
    result[key] = value;
  }
  return result;
}

export function updateFixtureEnv(text: string, changes: FixtureEnv): string {
  parseFixtureEnv(text);
  for (const [key, value] of Object.entries(changes)) {
    if (!allowed(key) || typeof value !== 'string' || /[\r\n\0]/.test(value)) throw invalid();
  }
  const newline = text.match(/\r\n|\n|\r/)?.[0] ?? '\n';
  const remaining = new Set(Object.keys(changes));
  const parts = text.split(/(\r\n|\n|\r)/);
  for (let index = 0; index < parts.length; index += 2) {
    const match = assignment(parts[index]);
    if (match && allowed(match[1]) && remaining.delete(match[1])) {
      parts[index] = `${match[1]}=${JSON.stringify(changes[match[1]])}`;
    }
  }
  let result = parts.join('');
  for (const key of remaining) {
    if (result && !/[\r\n]$/.test(result)) result += newline;
    result += `${key}=${JSON.stringify(changes[key as FixtureKey])}${newline}`;
  }
  return result;
}

function readText(): string {
  try { return readFileSync(envPath, 'utf8'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return ''; throw new Error('Cannot read fixture environment file'); }
}

export function readFixtureEnv(): FixtureEnv { return parseFixtureEnv(readText()); }

export function writeFixtureEnv(changes: FixtureEnv): void {
  const updated = updateFixtureEnv(readText(), changes);
  const temporary = `${envPath}.${randomUUID()}.tmp`;
  try {
    writeFileSync(temporary, updated, { mode: 0o600 });
    renameSync(temporary, envPath);
  } catch { throw new Error('Cannot write fixture environment file'); }
  finally { rmSync(temporary, { force: true }); }
}
