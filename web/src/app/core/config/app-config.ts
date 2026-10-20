import { Injectable } from '@angular/core';

export interface AppConfig {
  apiBaseUrl: string;
  wsUrl: string;
  cognito: {
    region: string;
    userPoolId: string;
    userPoolClientId: string;
  };
  demoCheckoutEnabled?: boolean;
}

const REQUIRED_FIELDS: readonly (readonly string[])[] = [
  ['apiBaseUrl'],
  ['wsUrl'],
  ['cognito', 'region'],
  ['cognito', 'userPoolId'],
  ['cognito', 'userPoolClientId'],
];

function readField(source: unknown, path: readonly string[]): unknown {
  let current: unknown = source;
  for (const key of path) {
    if (typeof current !== 'object' || current === null) {
      return undefined;
    }
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}

function validate(raw: unknown): AppConfig {
  for (const path of REQUIRED_FIELDS) {
    const value = readField(raw, path);
    if (typeof value !== 'string' || value.trim() === '') {
      throw new Error(`Invalid /config.json: missing ${path.join('.')}`);
    }
  }

  const demoCheckoutEnabled = readField(raw, ['demoCheckoutEnabled']);
  if (demoCheckoutEnabled !== undefined && typeof demoCheckoutEnabled !== 'boolean') {
    throw new Error('Invalid /config.json: demoCheckoutEnabled must be a boolean');
  }

  return {
    ...(raw as AppConfig),
    demoCheckoutEnabled: demoCheckoutEnabled ?? false,
  } as AppConfig;
}

@Injectable({ providedIn: 'root' })
export class AppConfigService {
  private loaded: AppConfig | undefined;

  get config(): AppConfig {
    if (!this.loaded) {
      throw new Error('AppConfigService.config read before load() completed');
    }
    return this.loaded;
  }

  async load(): Promise<void> {
    const response = await fetch('/config.json', { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`Cannot load /config.json: HTTP ${response.status}`);
    }
    this.loaded = validate(await response.json());
  }
}
