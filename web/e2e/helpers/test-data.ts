export interface SeedData {
  runId: string;
  hardwareCategoryId: string;
  softwareCategoryId: string;
  hardwareAttributeId: string;
  softwareAttributeId: string;
  hardwareListingId: string;
  softwareListingId: string;
  publicFixtures?: { noPhotoListingId: string; longListingId: string; longTitle: string; longAttribute: string };
  storageStates: { buyer: string; seller: string; admin: string };
}

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runDirectory } from './cognito-admin';

export function serializeSeed(data: SeedData): string {
  const { runId, hardwareCategoryId, softwareCategoryId, hardwareAttributeId, softwareAttributeId,
    hardwareListingId, softwareListingId } = data;
  return JSON.stringify({ runId, hardwareCategoryId, softwareCategoryId, hardwareAttributeId, softwareAttributeId,
    hardwareListingId, softwareListingId, ...(data.publicFixtures ? { publicFixtures: {
      noPhotoListingId: data.publicFixtures.noPhotoListingId, longListingId: data.publicFixtures.longListingId,
      longTitle: data.publicFixtures.longTitle, longAttribute: data.publicFixtures.longAttribute,
    } } : {}), storageStates: { buyer: data.storageStates.buyer,
      seller: data.storageStates.seller, admin: data.storageStates.admin } }, null, 2);
}

export function testData(): SeedData {
  return JSON.parse(readFileSync(resolve(runDirectory(process.env['E2E_RUN_ID'] ?? ''), 'seed.json'), 'utf8'));
}
