import { inject, InjectionToken } from '@angular/core';
import type { AppConfig } from '../config/app-config';
import { AmplifyAuthAdapter } from './amplify-auth.adapter';

export interface AuthIdentity {
  readonly sub: string;
  readonly email?: string;
  readonly groups: readonly string[];
}

export type AuthEvent = 'signedIn' | 'signedOut' | 'tokenRefresh' | 'tokenRefresh_failure';
export interface AccessTokenOptions {
  forceRefresh?: boolean;
}

export interface SignInResult {
  isSignedIn: boolean;
  nextStep: { signInStep: string };
}

export interface CodeDeliveryDetails {
  destination?: string;
  deliveryMedium?: string;
  attributeName?: string;
}

export interface SignUpResult {
  isSignUpComplete: boolean;
  userId?: string;
  nextStep: { signUpStep: string; codeDeliveryDetails?: CodeDeliveryDetails };
}

export interface ResetPasswordResult {
  isPasswordReset: boolean;
  nextStep: { resetPasswordStep: string; codeDeliveryDetails?: CodeDeliveryDetails };
}

export interface AuthAdapter {
  configure(config: AppConfig['cognito']): void;
  signUp(input: { email: string; password: string }): Promise<SignUpResult>;
  confirmSignUp(input: { email: string; code: string }): Promise<SignUpResult>;
  resendSignUpCode(email: string): Promise<void>;
  autoSignIn(): Promise<SignInResult>;
  signIn(input: { email: string; password: string }): Promise<SignInResult>;
  resetPassword(email: string): Promise<ResetPasswordResult>;
  confirmResetPassword(input: { email: string; code: string; newPassword: string }): Promise<void>;
  updatePassword(input: { oldPassword: string; newPassword: string }): Promise<void>;
  signOut(): Promise<void>;
  fetchSession(): Promise<AuthIdentity | null>;
  accessToken(options?: AccessTokenOptions): Promise<string | null>;
  onAuthEvent(listener: (event: AuthEvent) => void): () => void;
}

export const AuthAdapter = new InjectionToken<AuthAdapter>('AuthAdapter', {
  providedIn: 'root',
  factory: () => inject(AmplifyAuthAdapter),
});
