import { TestBed } from '@angular/core/testing';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { AppConfigService } from './app-config';
import demoConfig from '../../../../public/config.demo.json';

const VALID = {
  apiBaseUrl: 'http://localhost:8080',
  wsUrl: 'ws://localhost:8080/ws',
  cognito: { region: 'eu-south-1', userPoolId: 'pool', userPoolClientId: 'client' },
};

function stubFetch(body: unknown, ok = true, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok, status, json: () => Promise.resolve(body) }),
  );
}

describe('AppConfigService', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('loads only public settings from the committed demo config', async () => {
    expect(Object.keys(demoConfig).sort()).toEqual([
      'apiBaseUrl', 'cognito', 'demoCheckoutEnabled', 'wsUrl',
    ]);
    expect(Object.keys(demoConfig.cognito).sort()).toEqual([
      'region', 'userPoolClientId', 'userPoolId',
    ]);
    expect(demoConfig.apiBaseUrl).toBe('https://api.rebyte.tech');
    expect(demoConfig.wsUrl).toBe('wss://api.rebyte.tech/ws');
    expect(demoConfig.cognito).toEqual({
      region: 'eu-south-1',
      userPoolId: 'eu-south-1_tG4BJNs2r',
      userPoolClientId: '1fvpct0cb1ui35rhmp54qesrrf',
    });
    stubFetch(demoConfig);
    const service = TestBed.inject(AppConfigService);
    await service.load();
    expect(service.config.demoCheckoutEnabled).toBe(true);
  });

  it.each([null, 'true', 'false', 0, 1, {}, []])(
    'rejects a nonboolean demo flag: %j', async (demoCheckoutEnabled) => {
      stubFetch({ ...VALID, demoCheckoutEnabled });
      await expect(TestBed.inject(AppConfigService).load()).rejects.toThrow(
        'demoCheckoutEnabled must be a boolean',
      );
    },
  );

  it('preserves an explicitly disabled demo flag', async () => {
    stubFetch({ ...VALID, demoCheckoutEnabled: false });
    const service = TestBed.inject(AppConfigService);
    await service.load();
    expect(service.config.demoCheckoutEnabled).toBe(false);
  });

  it('loads a valid config file', async () => {
    stubFetch(VALID);
    const service = TestBed.inject(AppConfigService);
    await service.load();
    expect(service.config).toEqual({ ...VALID, demoCheckoutEnabled: false });
  });

  it('rejects a file with a missing nested field', async () => {
    stubFetch({ ...VALID, cognito: { region: 'eu-south-1', userPoolId: 'pool' } });
    const service = TestBed.inject(AppConfigService);
    await expect(service.load()).rejects.toThrow(
      'Invalid /config.json: missing cognito.userPoolClientId',
    );
  });

  it('rejects a file with an empty top-level field', async () => {
    stubFetch({ ...VALID, apiBaseUrl: '' });
    const service = TestBed.inject(AppConfigService);
    await expect(service.load()).rejects.toThrow('Invalid /config.json: missing apiBaseUrl');
  });

  it('rejects when the file cannot be fetched', async () => {
    stubFetch({}, false, 404);
    const service = TestBed.inject(AppConfigService);
    await expect(service.load()).rejects.toThrow('HTTP 404');
  });

  it('serves the shared test config without fetching', () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    TestBed.configureTestingModule({ providers: [provideTestAppConfig()] });
    expect(TestBed.inject(AppConfigService).config).toBe(TEST_APP_CONFIG);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
