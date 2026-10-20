export function fixtureTargets() {
  return {
    environment: 'demo',
    aws: { accountId: '000000000000', region: 'eu-south-1', poolId: 'eu-south-1_tG4BJNs2r', clientId: '1fvpct0cb1ui35rhmp54qesrrf', bucket: 'rebyte-demo-media-000000000000', cdnDistributionId: 'E000000000FAKE', iamUsers: { user: 'rebyte-demo-user-service', catalog: 'rebyte-demo-catalog-service', messaging: 'rebyte-demo-messaging-service' }, sesQueueArn: 'arn:aws:sqs:eu-south-1:000000000000:rebyte-demo-ses-events-messaging' },
    preservedSubjects: Object.fromEntries(['buyer', 'seller', 'developer', 'admin'].map((role, i) => [role, `00000000-0000-4000-8000-00000000000${i + 1}`])),
    atlas: { cluster: 'rebyte-dev', databases: { catalog: 'catalog_demo', messaging: 'messaging_demo' }, users: { catalog: 'catalog-demo', messaging: 'messaging-demo' } },
    stores: { mysqlResource: 'fakebackendresource00000-mysql-data', mysqlSchemas: ['user_demo', 'order_demo', 'payment_demo'], redisResource: 'fakebackendresource00000-redis-data', rabbitResource: 'fakebackendresource00000-rabbitmq-data', searchResource: 'fakebackendresource00000-meili-data' },
    coolify: { serverId: 'fakecoolifyserver0000000', backendResourceId: 'fakebackendresource00000', frontendResourceId: 'fakefrontendresource0000', network: 'fakebackendresource00000-backend' },
  };
}
export function observedTargets() {
  const targets = fixtureTargets();
  return { targets, accounts: Object.fromEntries(Object.entries(targets.preservedSubjects).map(([role, subject]) => [role, { subject, groups: role === 'admin' ? ['ADMIN'] : [], enabled: true, emailVerified: true, authenticationVerified: true }])) };
}
