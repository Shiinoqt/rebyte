import { Provider } from '@angular/core';
import { AppConfig, AppConfigService } from '../app/core/config/app-config';

export const TEST_APP_CONFIG: AppConfig = {
  apiBaseUrl: 'http://api.test',
  wsUrl: 'ws://api.test/ws',
  cognito: {
    region: 'eu-south-1',
    userPoolId: 'eu-south-1_TESTPOOL',
    userPoolClientId: 'testclientid',
  },
};

export function provideTestAppConfig(): Provider {
  const stub: Pick<AppConfigService, 'config' | 'load'> = {
    config: TEST_APP_CONFIG,
    load: () => Promise.resolve(),
  };
  return { provide: AppConfigService, useValue: stub };
}
