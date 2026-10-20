export function fixtureTargets() {
  return {
    environment: 'demo',
    aws: { accountId: '577101744401', region: 'eu-south-1', poolId: 'eu-south-1_tG4BJNs2r', clientId: '1fvpct0cb1ui35rhmp54qesrrf', bucket: 'rebyte-demo-media-577101744401', cdnDistributionId: 'E1WLORXTJ4LN20', iamUsers: { user: 'rebyte-demo-user-service', catalog: 'rebyte-demo-catalog-service', messaging: 'rebyte-demo-messaging-service' }, sesQueueArn: 'arn:aws:sqs:eu-south-1:577101744401:rebyte-demo-ses-events-messaging' },
    preservedSubjects: Object.fromEntries(['buyer', 'seller', 'developer', 'admin'].map((role, i) => [role, `00000000-0000-4000-8000-00000000000${i + 1}`])),
    atlas: { cluster: 'rebyte-dev', databases: { catalog: 'catalog_demo', messaging: 'messaging_demo' }, users: { catalog: 'catalog-demo', messaging: 'messaging-demo' } },
    stores: { mysqlResource: 'abrj06cdsga8lngmfbhyz3ek-mysql-data', mysqlSchemas: ['user_demo', 'order_demo', 'payment_demo'], redisResource: 'abrj06cdsga8lngmfbhyz3ek-redis-data', rabbitResource: 'abrj06cdsga8lngmfbhyz3ek-rabbitmq-data', searchResource: 'abrj06cdsga8lngmfbhyz3ek-meili-data' },
    coolify: { serverId: 'mrmr7pu5a7hlwg4avmtlsn4p', backendResourceId: 'abrj06cdsga8lngmfbhyz3ek', frontendResourceId: 'x8xwz0gzgng5bdlpnvutpvou', network: 'abrj06cdsga8lngmfbhyz3ek-backend' },
  };
}
export function observedTargets() {
  const targets = fixtureTargets();
  return { targets, accounts: Object.fromEntries(Object.entries(targets.preservedSubjects).map(([role, subject]) => [role, { subject, groups: role === 'admin' ? ['ADMIN'] : [], enabled: true, emailVerified: true, authenticationVerified: true }])) };
}
