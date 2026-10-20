import { inject, Injectable, InjectionToken } from '@angular/core';
import { Amplify } from 'aws-amplify';
import {
  autoSignIn,
  confirmResetPassword,
  confirmSignUp,
  fetchAuthSession,
  resendSignUpCode,
  resetPassword,
  signIn,
  signOut,
  signUp,
  updatePassword,
} from 'aws-amplify/auth';
import { Hub } from 'aws-amplify/utils';
import type { AppConfig } from '../config/app-config';
import type {
  AccessTokenOptions,
  AuthAdapter,
  AuthEvent,
  AuthIdentity,
  ResetPasswordResult,
  SignInResult,
  SignUpResult,
} from './auth.adapter';

const AMPLIFY_DEFAULT = {
  configure: (config: Parameters<typeof Amplify.configure>[0]) => Amplify.configure(config),
  listen: Hub.listen.bind(Hub),
  fetchAuthSession,
  signUp,
  confirmSignUp,
  resendSignUpCode,
  autoSignIn,
  signIn,
  resetPassword,
  confirmResetPassword,
  updatePassword,
  signOut,
};
export type AmplifySdk = typeof AMPLIFY_DEFAULT;

// Injected rather than imported at call sites so specs can replace the SDK without module mocking,
// which leaks across spec files that share a test worker.
export const AMPLIFY_SDK = new InjectionToken<AmplifySdk>('AmplifySdk', {
  providedIn: 'root',
  factory: () => AMPLIFY_DEFAULT,
});

@Injectable({ providedIn: 'root' })
export class AmplifyAuthAdapter implements AuthAdapter {
  private readonly sdk = inject(AMPLIFY_SDK);

  configure(config: AppConfig['cognito']): void {
    this.sdk.configure({
      Auth: {
        Cognito: {
          userPoolId: config.userPoolId,
          userPoolClientId: config.userPoolClientId,
          loginWith: { email: true },
        },
      },
    });
  }

  signUp({ email, password }: { email: string; password: string }): Promise<SignUpResult> {
    return this.sdk.signUp({
      username: email,
      password,
      options: { userAttributes: { email }, autoSignIn: true },
    });
  }

  confirmSignUp({ email, code }: { email: string; code: string }): Promise<SignUpResult> {
    return this.sdk.confirmSignUp({ username: email, confirmationCode: code });
  }

  autoSignIn(): Promise<SignInResult> {
    return this.sdk.autoSignIn();
  }

  signIn({ email, password }: { email: string; password: string }): Promise<SignInResult> {
    return this.sdk.signIn({ username: email, password });
  }

  resetPassword(email: string): Promise<ResetPasswordResult> {
    return this.sdk.resetPassword({ username: email });
  }

  confirmResetPassword({
    email,
    code,
    newPassword,
  }: {
    email: string;
    code: string;
    newPassword: string;
  }): Promise<void> {
    return this.sdk.confirmResetPassword({ username: email, confirmationCode: code, newPassword });
  }

  updatePassword(input: { oldPassword: string; newPassword: string }): Promise<void> {
    return this.sdk.updatePassword(input);
  }

  async resendSignUpCode(email: string): Promise<void> {
    await this.sdk.resendSignUpCode({ username: email });
  }

  signOut(): Promise<void> {
    return this.sdk.signOut();
  }

  async fetchSession(): Promise<AuthIdentity | null> {
    const { tokens } = await this.sdk.fetchAuthSession();
    const access = tokens?.accessToken?.payload;
    if (typeof access?.sub !== 'string' || !access.sub) {
      return null;
    }
    const groups = access['cognito:groups'];
    const email = tokens?.idToken?.payload['email'];
    return {
      sub: access.sub,
      ...(typeof email === 'string' ? { email } : {}),
      groups: Array.isArray(groups)
        ? groups.filter((group): group is string => typeof group === 'string')
        : [],
    };
  }

  async accessToken(options?: AccessTokenOptions): Promise<string | null> {
    try {
      return (await this.sdk.fetchAuthSession(options)).tokens?.accessToken?.toString() ?? null;
    } catch {
      return null;
    }
  }

  onAuthEvent(listener: (event: AuthEvent) => void): () => void {
    return this.sdk.listen('auth', ({ payload }) => {
      switch (payload.event) {
        case 'signedIn':
        case 'signedOut':
        case 'tokenRefresh':
        case 'tokenRefresh_failure':
          listener(payload.event);
      }
    });
  }
}
