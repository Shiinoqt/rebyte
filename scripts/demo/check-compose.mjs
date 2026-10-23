import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const requiredNames = (source) =>
  [
    ...new Set(
      JSON.stringify(source)
        .matchAll(/\$\{([A-Z][A-Z0-9_]*):\?/g)
        .map((m) => m[1]),
    ),
  ].sort();
const jvmServices = [
  "discovery-server",
  "api-gateway",
  "user-service",
  "catalog-service",
  "order-service",
  "payment-service",
  "messaging-service",
];
const stores = ["mysql-app", "redis", "rabbitmq", "meilisearch"];
const expectedRequired =
  "AWS_REGION CATALOG_AWS_ACCESS_KEY_ID CATALOG_AWS_SECRET_ACCESS_KEY CATALOG_MONGO_URI CHECKOUT_RETURN_BASE_URL COGNITO_USER_POOL_ID COOLIFY_PROXY_NETWORK DEMO_IMAGE_TAG DEMO_RESOURCE_ID GATEWAY_TRUSTED_PROXIES GHCR_NAMESPACE MEDIA_BUCKET MEDIA_DISTRIBUTION_DOMAIN MEILI_MASTER_KEY MESSAGING_AWS_ACCESS_KEY_ID MESSAGING_AWS_SECRET_ACCESS_KEY MESSAGING_MONGO_URI MESSAGING_PUBLIC_API_URL MESSAGING_UNSUBSCRIBE_SECRET MESSAGING_WEB_BASE_URL MYSQL_PASSWORD MYSQL_ROOT_PASSWORD PAYPAL_BASE_URL PAYPAL_CLIENT_ID PAYPAL_CLIENT_SECRET PAYPAL_WEBHOOK_ID RABBITMQ_PASSWORD RABBITMQ_USER REDIS_PASSWORD SES_CONFIGURATION_SET SES_EVENTS_QUEUE_URL SES_FROM_ADDRESS SPA_ALLOWED_ORIGINS USER_AWS_ACCESS_KEY_ID USER_AWS_SECRET_ACCESS_KEY".split(
    " ",
  );
const at = (model, name) => model.services[name].environment;

export function fakeEnvironment(source) {
  const env = Object.fromEntries(
    requiredNames(source).map((name) => [
      name,
      `fake-${name.toLowerCase()}-09`,
    ]),
  );
  return Object.assign(env, {
    DEMO_RESOURCE_ID: "fakecoolifyresource09",
    COOLIFY_PROXY_NETWORK: "coolify",
    GHCR_NAMESPACE: "ghcr.io/shiinoqt/rebyte",
    DEMO_IMAGE_TAG: "a".repeat(40),
    AWS_REGION: "eu-south-1",
    COGNITO_USER_POOL_ID: "eu-south-1_demo09",
    MEDIA_BUCKET: "rebyte-demo-media-577101744401",
    MEDIA_DISTRIBUTION_DOMAIN: "d1r2icevuc6b4b.cloudfront.net",
    CATALOG_MONGO_URI:
      "mongodb+srv://catalog-demo:fake-only@cluster.mongodb.net/catalog_demo?authSource=admin",
    MESSAGING_MONGO_URI:
      "mongodb+srv://messaging-demo:fake-only@cluster.mongodb.net/messaging_demo?authSource=admin",
    SPA_ALLOWED_ORIGINS: "https://rebyte.tech",
    CHECKOUT_RETURN_BASE_URL: "https://rebyte.tech",
    MESSAGING_WEB_BASE_URL: "https://rebyte.tech",
    MESSAGING_PUBLIC_API_URL: "https://rebyte.tech",
    PAYPAL_BASE_URL: "https://api-m.sandbox.paypal.com",
    GATEWAY_TRUSTED_PROXIES: "^10\\.0\\.1\\.6$",
    MESSAGING_UNSUBSCRIBE_SECRET: "fake-only-32-byte-unsubscribe-secret-09",
  });
}

export function renderCompose(root, values, { refuse = false } = {}) {
  const directory = mkdtempSync(join(tmpdir(), "rebyte-compose-check-"));
  try {
    const source = JSON.parse(
      readFileSync(join(root, "infra/docker-compose.demo.yml"), "utf8"),
    );
    const env = { ...process.env, COMPOSE_DISABLE_ENV_FILE: "1" };
    for (const name of requiredNames(source)) delete env[name];
    const file = join(directory, "fake.env");
    writeFileSync(
      file,
      Object.entries(values)
        .map(([name, value]) => `${name}='${value}'`)
        .join("\n"),
      { mode: 0o600 },
    );
    const result = spawnSync(
      "docker",
      [
        "compose",
        "--env-file",
        file,
        "-f",
        join(root, "infra/docker-compose.demo.yml"),
        "config",
        ...(refuse ? ["--quiet"] : ["--format", "json"]),
      ],
      {
        cwd: directory,
        env,
        encoding: "utf8",
        shell: false,
        windowsHide: true,
        timeout: 30000,
        maxBuffer: 4 * 1024 * 1024,
      },
    );
    assert.ifError(result.error);
    if (refuse) {
      assert.notEqual(
        result.status,
        0,
        "missing required variable must refuse",
      );
      return;
    }
    assert.equal(result.status, 0, "fake Compose render must succeed");
    // Compose escapes dollars for reuse as a Compose file; inspect the eventual container values.
    return JSON.parse(result.stdout, (_key, value) =>
      typeof value === "string" ? value.replaceAll("$$", "$") : value,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

export function validateModel(model) {
  const runner=model.services['demo-runner'];assert.ok(runner);
  assert.equal(runner.image,model.services['api-gateway'].image.replace('/api-gateway:','/demo-runner:'));
  assert.equal(runner.user,'10001:10001');assert.equal(runner.read_only,true);
  assert.deepEqual(runner.environment,{TZ:'Europe/Rome'});
  assert.deepEqual(runner.cap_drop,['ALL']);assert.deepEqual(runner.security_opt,['no-new-privileges:true']);
  assert.equal(runner.volumes?.length,1);assert.equal(Number(runner.mem_limit),768*1024*1024);
  assert.ok(runner.tmpfs.some(value=>value.startsWith('/run/demo-reset:')&&value.includes('uid=10001')&&value.includes('gid=10001')));
  assert.deepEqual(
    Object.keys(model.services).sort(),
    [...jvmServices, ...stores, 'demo-runner'].sort(),
    "exact private service universe",
  );
  for (const [name, service] of Object.entries(model.services)) {
    assert.ok(
      service.image && !service.build && !service.ports?.length,
      `${name}: image only, no host ports`,
    );
    assert.equal(service.platform, "linux/amd64");
    assert.ok(
      !service.privileged &&
        !service.network_mode &&
        !service.pid &&
        !service.ipc &&
        !service.devices &&
        !service.cap_add,
      `${name}: no host privileges`,
    );
    assert.ok(
      /^\d+$/.test(String(service.mem_limit)) &&
        Number.isSafeInteger(Number(service.mem_limit)) &&
        Number(service.mem_limit) > 0,
      `${name}: bounded memory`,
    );
    assert.ok(
      service.healthcheck?.test?.length > 0 &&
        service.restart === "unless-stopped",
    );
    for (const mount of service.volumes ?? []){
      assert.ok(mount.type === "volume", `${name}: no host bind mounts`);
      if(name==='demo-runner')assert.ok(mount.source==='reset-config'&&mount.target==='/run/secrets/demo-reset'&&mount.read_only===true&&mount.volume?.nocopy===true,'only the external read-only config volume may be mounted');
      else assert.notEqual(mount.source,'reset-config','private config belongs only to the runner');
    }
    assert.deepEqual(
      Object.keys(service.networks).sort(),
      name === "api-gateway" ? ["backend", "proxy"] : ["backend"],
    );
    assert.ok(
      service.environment &&
        Object.values(service.environment).every(
          (value) => typeof value === "string" && value.trim(),
        ),
      `${name}: no empty env`,
    );
    if (jvmServices.includes(name)) {
      assert.match(service.image, /^ghcr\.io\/[a-z0-9._/-]+:[a-f0-9]{40}$/);
      assert.equal(service.environment.SPRING_PROFILES_ACTIVE, "cognito,demo");
      assert.equal(
        service.environment.JAVA_TOOL_OPTIONS,
        "-XX:MaxRAMPercentage=35 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m",
      );
      if (name !== "discovery-server") {
        assert.equal(service.environment.AWS_REGION, "eu-south-1");
        assert.match(service.environment.COGNITO_USER_POOL_ID, /^eu-south-1_/);
        assert.notEqual(
          service.environment.COGNITO_USER_POOL_ID,
          "eu-south-1_WPMqs7BW8",
        );
        assert.equal(
          service.environment.COGNITO_USER_POOL_ID,
          at(model, "api-gateway").COGNITO_USER_POOL_ID,
        );
      }
    }
  }
  for (const [name, volume] of Object.entries(model.volumes))
    assert.equal(
      volume.name,
      `${model.networks.backend.name.replace(/-backend$/, "")}-${name}`,
    );
  assert.deepEqual(
    Object.keys(model.volumes).sort(),
    ["mysql-data", "redis-data", "rabbitmq-data", "meili-data", "reset-config"].sort(),
  );
  assert.equal(model.volumes['reset-config'].external,true,'config volume must already exist');
  assert.ok(model.networks.backend.external && model.networks.proxy.external);
  assert.match(model.networks.backend.name, /^[a-z0-9]+-backend$/);
  assert.equal(model.networks.proxy.name, "coolify");
  const redis = at(model, "redis").REDIS_PASSWORD;
  assert.equal(model.services.redis.user, "redis");
  assert.deepEqual(model.services.redis.networks.backend.aliases, ["rebyte-redis"]);
  // Proxy-network members also resolve Coolify's own containers by these names.
  const coolifyNames = ["coolify", "coolify-db", "postgres", "coolify-proxy", "traefik", "coolify-realtime", "coolify-sentinel", "coolify-redis", "redis"];
  for (const [name, service] of Object.entries(model.services).filter(([, s]) => "proxy" in (s.networks ?? {})))
    for (const value of Object.values(service.environment ?? {}))
      for (const host of coolifyNames)
        assert.ok(value !== host && !value.includes(`//${host}:`) && !value.includes(`//${host}/`), `${name}: ${host} resolves to Coolify on the proxy network`);
  for (const name of ["api-gateway", "catalog-service", "messaging-service"]) {
    assert.equal(at(model, name).REDIS_HOST, "rebyte-redis");
    assert.equal(
      at(model, name).SPRING_DATA_REDIS_PASSWORD,
      redis,
      "authenticated Redis consumers",
    );
    assert.equal(at(model, name).SPRING_DATA_REDIS_TIMEOUT, "2s");
    assert.equal(at(model, name).SPRING_DATA_REDIS_CONNECT_TIMEOUT, "2s");
  }
  assert.ok(
    model.services.redis.command
      .join(" ")
      .includes('--requirepass "$REDIS_PASSWORD"'),
  );
  assert.ok(
    model.services.redis.healthcheck.test
      .join(" ")
      .includes('REDISCLI_AUTH="$REDIS_PASSWORD"'),
  );
  for (const role of ["user", "order", "payment"]) {
    const env = at(model, `${role}-service`);
    assert.equal(
      env.SPRING_DATASOURCE_URL,
      `jdbc:mysql://mysql-app:3306/${role}_demo`,
    );
    assert.equal(env.MYSQL_USER, "rebyte");
    assert.equal(env.MYSQL_PASSWORD, at(model, "mysql-app").MYSQL_PASSWORD);
    assert.ok(
      model.configs["mysql-init"].content.includes(
        `CREATE DATABASE IF NOT EXISTS ${role}_demo `,
      ),
    );
  }
  const mongoUsers = [];
  for (const role of ["catalog", "messaging"]) {
    const uri = new URL(at(model, `${role}-service`).MONGO_URI);
    assert.equal(uri.protocol, "mongodb+srv:");
    assert.equal(uri.pathname, `/${role}_demo`);
    assert.ok(
      uri.username && uri.password && uri.hostname.endsWith(".mongodb.net"),
    );
    mongoUsers.push(uri.username);
  }
  assert.notEqual(...mongoUsers);
  const keys = [];
  for (const role of ["user", "catalog", "messaging"]) {
    const env = at(model, `${role}-service`);
    const prefix = role === "user" ? "" : `${role.toUpperCase()}_`;
    assert.ok(
      env[`${prefix}AWS_ACCESS_KEY_ID`] &&
        env[`${prefix}AWS_SECRET_ACCESS_KEY`],
      "service scoped IAM",
    );
    if (role !== "user")
      assert.ok(!env.AWS_ACCESS_KEY_ID && !env.AWS_SECRET_ACCESS_KEY);
    keys.push(env[`${prefix}AWS_ACCESS_KEY_ID`]);
    assert.equal(env.MEDIA_BUCKET, "rebyte-demo-media-577101744401");
    assert.equal(env.MEDIA_REGION, "eu-south-1");
  }
  assert.equal(new Set(keys).size, 3);
  for (const name of jvmServices.filter(
    (name) => !["discovery-server", "api-gateway"].includes(name),
  )) {
    assert.equal(
      at(model, name).RABBITMQ_USER,
      at(model, "rabbitmq").RABBITMQ_DEFAULT_USER,
    );
    assert.equal(
      at(model, name).RABBITMQ_PASSWORD,
      at(model, "rabbitmq").RABBITMQ_DEFAULT_PASS,
    );
  }
  const releases = [...jvmServices, "rabbitmq"].map((name) =>
    model.services[name].image.replace(`/${name}:`, ":"),
  );
  assert.equal(new Set(releases).size, 1, "one coordinated immutable release");
  assert.equal(
    at(model, "catalog-service").MEILI_MASTER_KEY,
    at(model, "meilisearch").MEILI_MASTER_KEY,
  );
  const gateway = at(model, "api-gateway");
  assert.equal(gateway.GATEWAY_PORT, "8080");
  assert.deepEqual(model.services["api-gateway"].expose, ["8080"]);
  assert.equal(gateway.SERVER_FORWARD_HEADERS_STRATEGY, "framework");
  assert.match(
    gateway.GATEWAY_TRUSTED_PROXIES,
    /^\^\d+\\\.\d+\\\.\d+\\\.\d+\$$/,
    "exact observed peer only",
  );
  assert.equal(gateway.GATEWAY_CORS_ALLOWED_ORIGINS, "https://rebyte.tech");
  const labels = model.services["api-gateway"].labels;
  assert.equal(labels["traefik.docker.network"], model.networks.proxy.name);
  assert.equal(
    labels["traefik.http.routers.rebyte-demo-api.rule"],
    "Host(`api.rebyte.tech`)",
  );
  assert.equal(
    labels["traefik.http.routers.rebyte-demo-api.entrypoints"],
    "https",
  );
  assert.equal(
    labels["traefik.http.routers.rebyte-demo-api.tls.certresolver"],
    "letsencrypt",
  );
  assert.equal(
    labels["traefik.http.services.rebyte-demo-api.loadbalancer.server.port"],
    "8080",
  );
  for (const name of Object.keys(model.services).filter(
    (name) => name !== "api-gateway",
  ))
    assert.ok(!model.services[name].labels?.["traefik.enable"]);
  const messaging = at(model, "messaging-service");
  for (const key of [
    "MESSAGING_WEB_BASE_URL",
    "MESSAGING_PUBLIC_API_URL",
    "MESSAGING_WS_ALLOWED_ORIGINS",
  ])
    assert.equal(messaging[key], "https://rebyte.tech");
  assert.ok(Buffer.byteLength(messaging.MESSAGING_UNSUBSCRIBE_SECRET) >= 32);
  assert.equal(messaging.SES_EVENTS_ENABLED, "true");
  assert.equal(
    at(model, "payment-service").PAYPAL_BASE_URL,
    "https://api-m.sandbox.paypal.com",
  );
  assert.equal(
    at(model, "payment-service").CHECKOUT_RETURN_BASE_URL,
    "https://rebyte.tech",
  );
  assert.equal(
    at(model, "catalog-service").MEDIA_ARTIFACT_MAX_SIZE_BYTES,
    "10485760",
  );
  assert.equal(
    at(model, "catalog-service").MEDIA_PHOTO_MAX_SIZE_BYTES,
    "1048576",
  );
  assert.equal(at(model, "user-service").MEDIA_AVATAR_MAX_SIZE_BYTES, "262144");
  assert.equal(messaging.MEDIA_CHAT_IMAGE_MAX_SIZE_BYTES, "524288");
  const memoryBytes = Object.values(model.services).reduce(
    (total, service) => total + Number(service.mem_limit),
    0,
  );
  assert.ok(
    memoryBytes <= 6400 * 1024 * 1024,
    "reviewed backend memory budget",
  );
  return { services: Object.keys(model.services).length, memoryBytes };
}

export function checkCompose(root = process.cwd()) {
  const source = JSON.parse(
    readFileSync(join(root, "infra/docker-compose.demo.yml"), "utf8"),
  );
  assert.deepEqual(
    requiredNames(source),
    expectedRequired,
    "all external settings must remain required",
  );
  // Coolify external-volume names need a bare ID; other references require that same ID.
  for (const match of JSON.stringify(source).matchAll(/\$\{([^}]+)\}/g))
    assert.match(
      match[1],
      /^(?:[A-Z][A-Z0-9_]*:\?Set [A-Z][A-Z0-9_]*|DEMO_RESOURCE_ID)$/,
      "no optional or unguarded settings",
    );
  const env = fakeEnvironment(source);
  assert.deepEqual(
    Object.keys(env).sort(),
    requiredNames(source),
    "fake env matches all required guards",
  );
  const result = validateModel(renderCompose(root, env));
  for (const name of Object.keys(env)) {
    const incomplete = { ...env };
    delete incomplete[name];
    renderCompose(root, incomplete, { refuse: true });
  }
  return {
    ...result,
    requiredVariables: Object.keys(env).length,
    omissionsRefused: Object.keys(env).length,
  };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    process.stdout.write(`${JSON.stringify(checkCompose())}\n`);
  } catch {
    process.stderr.write(
      "Demo Compose validation refused; no private configuration was read.\n",
    );
    process.exitCode = 1;
  }
}
