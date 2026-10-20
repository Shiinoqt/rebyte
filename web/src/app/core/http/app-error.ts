export interface AppError {
  readonly status: number;
  readonly code: string;
  readonly type: string;
  readonly fieldErrors: Readonly<Record<string, string>>;
  readonly extras: Readonly<Record<string, string | number>>;
}
