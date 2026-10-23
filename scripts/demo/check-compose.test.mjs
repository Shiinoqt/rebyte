import test from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  fakeEnvironment,
  renderCompose,
  validateModel,
  checkCompose,
} from "./check-compose.mjs";

const root = process.cwd();
const source = () =>
  JSON.parse(readFileSync("infra/docker-compose.demo.yml", "utf8"));
const model = () => renderCompose(root, fakeEnvironment(source()));

test("real Compose render and every required omission are exercised", () => {
  const result = checkCompose(root);
  assert.ok(result.requiredVariables >= 30);
  assert.equal(result.omissionsRefused, result.requiredVariables);
  assert.equal(result.services, 12);
});

for (const [name, mutate] of Object.entries({
  "published datastore": (m) => {
    m.services.redis.ports = [{ target: 6379, published: "6379" }];
  },
  "runner writable config":m=>{m.services['demo-runner'].volumes[0].read_only=false;},
  "runner config copied":m=>{m.services['demo-runner'].volumes[0].volume.nocopy=false;},
  "runner config created implicitly":m=>{m.volumes['reset-config'].external=false;},
  "runner config shared with application":m=>{m.services['user-service'].volumes=[{type:'volume',source:'reset-config',target:'/private'}];},
  "runner daemon mount":m=>{m.services['demo-runner'].volumes[0].source='/var/run/docker.sock';},
  "runner root user":m=>{m.services['demo-runner'].user='0:0';},
  "runner public network":m=>{m.services['demo-runner'].networks.proxy={};},
  "runner secret environment":m=>{m.services['demo-runner'].environment.AWS_SECRET_ACCESS_KEY='fixture';},
  "implicit build": (m) => {
    m.services["user-service"].build = { context: ".." };
  },
  "docker socket": (m) => {
    m.services["api-gateway"].volumes = [
      { type: "bind", source: "/var/run/docker.sock", target: "/socket" },
    ];
  },
  "privileged container": (m) => {
    m.services.redis.privileged = true;
  },
  "host network": (m) => {
    m.services.redis.network_mode = "host";
  },
  "missing memory cap": (m) => {
    delete m.services.redis.mem_limit;
  },
  "overcommitted memory": (m) => {
    m.services.redis.mem_limit = "17179869184";
  },
  "wrong broker credential": (m) => {
    m.services["order-service"].environment.RABBITMQ_PASSWORD = "different";
  },
  "different release": (m) => {
    m.services.rabbitmq.image = m.services.rabbitmq.image.replace(
      /a{40}/,
      "b".repeat(40),
    );
  },
  "public internal route": (m) => {
    m.services["user-service"].labels = { "traefik.enable": "true" };
  },
  "missing gateway route": (m) => {
    delete m.services["api-gateway"].labels[
      "traefik.http.routers.rebyte-demo-api.rule"
    ];
  },
  "missing Redis auth": (m) => {
    delete m.services["catalog-service"].environment.SPRING_DATA_REDIS_PASSWORD;
  },
  "root Redis process": (m) => {
    m.services.redis.user = "root";
  },
  "different Redis password": (m) => {
    m.services["messaging-service"].environment.SPRING_DATA_REDIS_PASSWORD =
      "other";
  },
  "dev MySQL schema": (m) => {
    m.services["user-service"].environment.SPRING_DATASOURCE_URL =
      "jdbc:mysql://mysql-app:3306/user_db";
  },
  "dev Mongo schema": (m) => {
    m.services["catalog-service"].environment.MONGO_URI =
      "mongodb+srv://catalog-demo:fake@cluster.mongodb.net/catalog_db";
  },
  "dev Cognito pool": (m) => {
    m.services["api-gateway"].environment.COGNITO_USER_POOL_ID =
      "eu-south-1_WPMqs7BW8";
  },
  "local auth": (m) => {
    m.services["order-service"].environment.SPRING_PROFILES_ACTIVE =
      "local-auth";
  },
  "public datastore network": (m) => {
    m.services.redis.networks.proxy = null;
  },
  "gateway port drift": (m) => {
    m.services["api-gateway"].environment.GATEWAY_PORT = "8081";
  },
  "gateway missing seed network": (m) => {
    delete m.services["api-gateway"].networks.backend;
  },
  "untrusted proxy": (m) => {
    m.services["api-gateway"].environment.GATEWAY_TRUSTED_PROXIES = ".*";
  },
  "wrong email unsubscribe origin": (m) => {
    m.services["messaging-service"].environment.MESSAGING_PUBLIC_API_URL =
      "https://api.rebyte.tech";
  },
  "live PayPal": (m) => {
    m.services["payment-service"].environment.PAYPAL_BASE_URL =
      "https://api-m.paypal.com";
  },
  "missing per-service IAM": (m) => {
    delete m.services["messaging-service"].environment
      .MESSAGING_AWS_SECRET_ACCESS_KEY;
  },
  "shared messaging credentials": (m) => {
    m.services["messaging-service"].environment.AWS_ACCESS_KEY_ID = "fake";
  },
  "wrong artifact cap": (m) => {
    m.services["catalog-service"].environment.MEDIA_ARTIFACT_MAX_SIZE_BYTES =
      "104857600";
  },
  "moving release tag": (m) => {
    m.services["api-gateway"].image =
      "ghcr.io/shiinoqt/rebyte/api-gateway:demo";
  },
  "unstable volume": (m) => {
    delete m.volumes["mysql-data"].name;
  },
  "maintenance route": (m) => {
    m.services.reset = { image: "fake" };
  },
})) {
  test(`refuses ${name}`, () => {
    const value = model();
    assert.doesNotThrow(() => validateModel(value));
    mutate(value);
    assert.throws(() => validateModel(value));
  });
}

test("template lists every required variable and contains no credentials", () => {
  const template = readFileSync("infra/.env.demo.example", "utf8");
  for (const name of Object.keys(fakeEnvironment(source())))
    assert.match(template, new RegExp(`^${name}=`, "m"));
  assert.doesNotMatch(
    template,
    /AKIA[A-Z0-9]{16}|BEGIN .*PRIVATE KEY|mongodb\+srv:\/\/[^\s]+:[^\s]+@/,
  );
});

for (const replacement of [
  "${RABBITMQ_PASSWORD:-fake}",
  "hardcoded-password",
]) {
  test(`refuses source with weakened guard: ${replacement}`, () => {
    const directory = mkdtempSync(join(tmpdir(), "rebyte-compose-mutation-"));
    try {
      mkdirSync(join(directory, "infra"));
      const original = readFileSync("infra/docker-compose.demo.yml", "utf8");
      writeFileSync(
        join(directory, "infra/docker-compose.demo.yml"),
        original.replaceAll(
          "${RABBITMQ_PASSWORD:?Set RABBITMQ_PASSWORD}",
          replacement,
        ),
      );
      assert.throws(() => checkCompose(directory));
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

// Coolify's raw Compose parser appends list-form labels to every service; a map would gain integer keys.
test("services still render after Coolify appends its labels", () => {
  const directory = mkdtempSync(join(tmpdir(), "rebyte-compose-coolify-"));
  try {
    mkdirSync(join(directory, "infra"));
    const compose = source();
    for (const service of Object.values(compose.services)) {
      assert.ok(service.labels === undefined || Array.isArray(service.labels));
      service.labels = [...(service.labels ?? []), "coolify.managed=true"];
    }
    writeFileSync(
      join(directory, "infra/docker-compose.demo.yml"),
      JSON.stringify(compose),
    );
    const labels = renderCompose(directory, fakeEnvironment(compose)).services[
      "api-gateway"
    ].labels;
    assert.equal(labels["coolify.managed"], "true");
    assert.equal(labels["traefik.enable"], "true");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("local service Compose only loses hardcoded PayPal base URL", () => {
  const text = readFileSync("infra/docker-compose.services.yml", "utf8");
  assert.doesNotMatch(text, /^\s+PAYPAL_BASE_URL:/m);
  assert.match(text, /USER_HOST_PORT/);
  assert.match(text, /build:/);
});
