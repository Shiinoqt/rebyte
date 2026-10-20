import { test, expect } from '@playwright/test';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { testData } from './helpers/test-data';
import { openSession } from './helpers/two-session';

test.use({ trace: 'off' });

function subject(path: string): string {
  try {
    const state = JSON.parse(readFileSync(path, 'utf8')) as {
      origins: { localStorage: { name: string; value: string }[] }[];
    };
    const token = state.origins
      .flatMap((origin) => origin.localStorage)
      .find((entry) => entry.name.endsWith('.accessToken'))?.value;
    if (!token) throw new Error();
    const identity = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    if (typeof identity.sub !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(identity.sub))
      throw new Error();
    return identity.sub;
  } catch {
    throw new Error('Saved test identity is invalid');
  }
}

function depths(): number[] {
  const result = spawnSync(
    'docker',
    [
      'exec',
      'rebyte-rabbitmq',
      'rabbitmqctl',
      'list_queues',
      'name',
      'messages_ready',
      'messages_unacknowledged',
    ],
    { shell: false, timeout: 30_000, encoding: 'utf8' },
  );
  expect(result.status, 'RabbitMQ fixture queue inspection failed').toBe(0);
  const rows = result.stdout.split(/\r?\n/).map((line) => line.trim().split(/\s+/));
  return ['notify.favourite.dlq', 'notify.favourite'].map((queue) => {
    const row = rows.find((values) => values[0] === queue);
    if (!row || !/^\d+$/.test(row[1]) || !/^\d+$/.test(row[2]))
      throw new Error('Replay fixture queue is unavailable');
    return Number(row[1]) + Number(row[2]);
  });
}

test('admin replays an owned notification fixture with an audit id @phone', async ({
  browser,
}, testInfo) => {
  test.setTimeout(60_000);
  const seed = testData();
  const admin = await openSession(browser, testInfo, seed.storageStates.admin);
  try {
    // Never consume a pre-existing message from a shared fixture queue.
    expect(depths()).toEqual([0, 0]);
    const eventId = randomUUID();
    const event = {
      eventId,
      userSub: subject(seed.storageStates.buyer),
      listingId: seed.hardwareListingId,
      sellerSub: subject(seed.storageStates.seller),
      listingTitle: 'Nyquist replay fixture',
      occurredAt: new Date().toISOString(),
      relationshipSequence: null,
    };
    const properties = {
      content_type: 'application/json',
      delivery_mode: 2,
      message_id: eventId,
      headers: { 'x-rebyte-original-routing-key': 'listing.unfavourited' },
    };
    const published = spawnSync(
      'docker',
      [
        'exec',
        '-i',
        'rebyte-rabbitmq',
        'sh',
        '-c',
        'export RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS"; exec rabbitmqadmin publish message --exchange "" --routing-key "$1" --properties "$2" --payload-file -',
        'nyquist-replay',
        'notify.favourite.dlq',
        JSON.stringify(properties),
      ],
      {
        input: JSON.stringify(event),
        shell: false,
        encoding: 'utf8',
        timeout: 30_000,
      },
    );
    expect(published.status, 'Replay fixture publication failed').toBe(0);
    expect(depths()).toEqual([1, 0]);

    const { page } = admin;
    await page.goto('/admin/operations');
    await page.locator('#messaging-queue').selectOption('notify.favourite.dlq');
    await page.getByRole('button', { name: 'Replay one messaging message', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Replay this message?', exact: true });
    await expect(dialog).toContainText('notify.favourite.dlq');
    const replayed = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname ===
          '/admin/messaging/dead-letters/notify.favourite.dlq/replay',
    );
    await dialog.getByRole('button', { name: 'Replay one message', exact: true }).click();
    const response = await replayed;
    expect(response.status()).toBe(200);
    const auditId = response.headers()['x-audit-id'];
    expect(auditId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    await expect(
      page.getByText('One message returned to processing.', { exact: true }),
    ).toBeVisible();
    await expect(page.getByText(auditId, { exact: true })).toBeVisible();
    await expect(dialog).toHaveCount(0);
    await expect.poll(depths, { timeout: 15_000 }).toEqual([0, 0]);
    admin.verify();
  } finally {
    await admin.context.close();
  }
});
