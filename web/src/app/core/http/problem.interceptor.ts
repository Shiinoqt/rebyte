import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { parseApiJson } from '../api/api-client';
import { AppError } from './app-error';

const CODE = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const GATEWAY_PROBLEMS = [
  { status: 401, code: 'unauthenticated', type: 'https://rebyte.it/problems/unauthenticated' },
  { status: 403, code: 'access-denied', type: 'https://rebyte.it/problems/access-denied' },
  { status: 429, code: 'rate-limited', type: 'https://rebyte.it/problems/rate-limited' },
] as const;

function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function problemType(value: unknown): string {
  return typeof value === 'string' &&
    (/^urn:rebyte:problem:[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value) ||
      /^https:\/\/rebyte\.it\/problems\/[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value))
    ? value
    : 'about:blank';
}

function parseBody(text: string): unknown {
  try {
    return parseApiJson(text);
  } catch {
    return null;
  }
}

function normalize(error: HttpErrorResponse): AppError {
  if (error.status === 0) {
    return { status: 0, code: 'offline', type: 'about:blank', fieldErrors: {}, extras: {} };
  }
  // ApiClient requests read bodies as text, so their problem JSON arrives here undecoded.
  const body = record(typeof error.error === 'string' ? parseBody(error.error) : error.error);
  const type = problemType(body['type']);
  const gateway =
    GATEWAY_PROBLEMS.find((problem) => problem.status === error.status && problem.type === type) ??
    GATEWAY_PROBLEMS.find((problem) => problem.status === error.status);
  const title = body['title'];
  const code =
    typeof title === 'string' && CODE.test(title) ? title : (gateway?.code ?? 'unknown-error');
  const fieldErrors: Record<string, string> = {};
  for (const [field, message] of Object.entries(record(body['errors']))) {
    if (typeof message === 'string' && !['__proto__', 'constructor', 'prototype'].includes(field)) {
      fieldErrors[field] = message;
    }
  }
  // Only backend extensions with a screen-level purpose cross this boundary.
  const extras: Record<string, string | number> = {};
  const currentTotal = body['currentTotal'];
  if (
    (typeof currentTotal === 'number' && Number.isFinite(currentTotal) && currentTotal >= 0) ||
    (typeof currentTotal === 'string' && /^\d+(\.\d+)?$/.test(currentTotal))
  ) {
    extras['currentTotal'] = currentTotal;
  }
  const conversationId = body['conversationId'];
  if (typeof conversationId === 'string' && /^[a-zA-Z0-9_-]+$/.test(conversationId)) {
    extras['conversationId'] = conversationId;
  }
  return { status: error.status, code, type, fieldErrors, extras };
}

export const problemInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((error: unknown) =>
      throwError(() => (error instanceof HttpErrorResponse ? normalize(error) : error)),
    ),
  );
