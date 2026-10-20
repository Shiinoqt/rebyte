export interface FixtureOrderState { status: string; review?: unknown; problem?: unknown }

export function assertFreshFixtures(hardware: FixtureOrderState, software: FixtureOrderState): void {
  const fresh = hardware.status === 'PAID' && !hardware.review && !hardware.problem &&
    software.status === 'DELIVERED' && !software.review && !software.problem;
  if (!fresh) throw new Error('fixture consumed: rerun npm --prefix web run e2e:fixtures');
}

// Admin resolution needs both fixture problems untouched, so a consumed fixture is reported with how to rebuild it.
export function assertOpenProblems(hardware: FixtureOrderState, software: FixtureOrderState): void {
  const open = (order: FixtureOrderState) => (order.problem as { status?: string } | undefined)?.status === 'OPEN';
  if (!open(hardware) || !open(software)) {
    throw new Error('fixture consumed: rerun npm --prefix web run e2e:fixtures and plan 06-42 Task 3');
  }
}
