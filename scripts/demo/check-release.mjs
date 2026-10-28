import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {stageRunnerContext,validateRunnerContext} from './runner-context.mjs';

export const IMAGE_NAMES = [
  "discovery-server",
  "api-gateway",
  "user-service",
  "catalog-service",
  "order-service",
  "payment-service",
  "messaging-service",
  "web",
  "rabbitmq",
  "demo-runner",
];
const read = (path) => readFileSync(path, "utf8");
const pinned = /^\S+@sha256:[a-f0-9]{64}$/;
const baseFamilies = {
  runtime: "eclipse-temurin:26-jre-noble",
  web: "nginx:1.30.6-alpine",
  rabbitmq: "rabbitmq:4.1-management",
};
const sourceRoot = fileURLToPath(new URL("../../", import.meta.url));
const upload = (name, path, extra) => ({
  name,
  path,
  "if-no-files-found": "error",
  "retention-days": 1,
  ...extra,
});
const download = (name, path) => ({ name, path });
const jobSpecs = {
  java: {
    timeout: 45,
    runs: [
      ["./mvnw -B verify -Dsurefire.skipAfterFailureCount=1 -Dfailsafe.skipAfterFailureCount=1", 35],
    ],
    downloads: [],
    upload: upload("demo-java-jars", "*/target/*.jar", { "compression-level": 0 }),
  },
  runner: {
    timeout: 45,
    runs: [
      ["docker build --tag rebyte/rabbitmq:4.1-delayed infra/rabbitmq", 5],
      ["node --test scripts/demo/*.test.mjs", 8],
      ["node scripts/demo/check-release.mjs", 1],
      ["node scripts/demo/runner-image-smoke.mjs", 25],
    ],
    downloads: [],
    upload: upload("demo-runner-smoke", "target/demo-runner-smoke/report.json"),
  },
  web: {
    timeout: 30,
    runs: [
      ["npm install --global npm@12.2.0", 3],
      ["npm --prefix web ci", 5],
      ["npm --prefix web run test:ci", 5],
      ["npm --prefix web run build", 5],
      ["node web/scripts/demo-image-smoke.mjs", 5],
    ],
    downloads: [],
    upload: upload("demo-web-dist", "web/dist"),
  },
  stage: {
    timeout: 15,
    needs: ["java", "runner", "web"],
    runs: [["node scripts/demo/check-release.mjs prepare release-contexts", 5]],
    downloads: [
      download("demo-java-jars", "."),
      download("demo-runner-smoke", "target/demo-runner-smoke"),
      download("demo-web-dist", "web/dist"),
    ],
    upload: upload("demo-contexts", "release-contexts", { "compression-level": 0 }),
  },
};
const actionRepos = [
  "actions/checkout",
  "actions/setup-java",
  "actions/setup-node",
  "actions/upload-artifact",
  "actions/download-artifact",
  "docker/setup-buildx-action",
  "docker/login-action",
];
const action = (job, repo) =>
  job.steps.filter((step) => step.uses?.startsWith(repo + "@"));

export function validateWorkflow(workflow, dockerfiles) {
  assert(
    !workflow.env &&
      !workflow.defaults &&
      Object.values(workflow.jobs).every((job) => !job.env),
    "no global or job environment bypass",
  );
  assert.deepEqual(
    workflow.on,
    {
      push: {
        branches: ["main"],
        "paths-ignore": [".planning/**", "docs/**", "*.md", "**/*.md"],
      },
    },
    "main code pushes only",
  );
  assert.deepEqual(
    workflow.permissions,
    { contents: "read" },
    "read-only default token",
  );
  assert.deepEqual(
    workflow.concurrency,
    { group: "demo-image-release", "cancel-in-progress": false },
    "serialize complete releases",
  );
  assert.deepEqual(
    Object.keys(workflow.jobs).sort(),
    ["java", "publish", "runner", "stage", "web"],
    "only gate, stage and publish jobs",
  );
  const { publish } = workflow.jobs;
  assert.equal(publish["timeout-minutes"], 30, "bounded publication runtime");
  assert.deepEqual(
    publish.permissions,
    { contents: "read", packages: "write" },
    "limited registry token",
  );
  assert.equal(publish.needs, "stage", "publish waits for staged contexts");
  for (const job of Object.values(workflow.jobs)) {
    assert.equal(
      job["runs-on"],
      "ubuntu-24.04",
      "hosted runner, never the VPS",
    );
    assert(
      !job.strategy &&
        !job["continue-on-error"] &&
        !job.if &&
        !job.container &&
        !job.defaults,
      "no bypass or rebuild matrix",
    );
    for (const step of job.steps) {
      assert(
        !step["continue-on-error"] &&
          !step.if &&
          !step.shell &&
          !step["working-directory"] &&
          !step.env,
        "steps cannot bypass gates",
      );
      if (step.uses) {
        const match = step.uses.match(/^([^@]+)@([a-f0-9]{40})$/);
        assert(
          match && actionRepos.includes(match[1]),
          "reviewed action repository and full SHA",
        );
      }
    }
    assert.equal(action(job, "actions/checkout").length, 1, "one checkout");
    assert.deepEqual(
      action(job, "actions/checkout")[0].with,
      { "persist-credentials": false },
      "no stored or custom checkout credentials",
    );
    assert.equal(action(job, "actions/setup-node").length, 1, "one Node setup");
    assert.deepEqual(
      action(job, "actions/setup-node")[0].with,
      { "node-version": "24.21.0", "package-manager-cache": false },
      "compatible verified Node setup",
    );
  }
  for (const [name, spec] of Object.entries(jobSpecs)) {
    const job = workflow.jobs[name];
    assert.equal(job["timeout-minutes"], spec.timeout, `bounded ${name} runtime`);
    assert.deepEqual(job.permissions, { contents: "read" }, `${name} cannot publish`);
    assert.deepEqual(job.needs, spec.needs, `${name} dependency chain`);
    assert.deepEqual(
      job.steps.filter((step) => step.run).map((step) => [step.run, step["timeout-minutes"]]),
      spec.runs,
      `${name} bounded verified commands`,
    );
    assert.equal(action(job, "actions/setup-java").length, name === "java" ? 1 : 0, "Java only where Maven runs");
    assert.equal(action(job, "docker/login-action").length, 0, "no build registry login");
    const downloads = action(job, "actions/download-artifact");
    assert.deepEqual(downloads.map((step) => step.with), spec.downloads, `${name} same-run artifact inputs`);
    assert(
      downloads.every((step) => job.steps.indexOf(step) < job.steps.findIndex((other) => other.run)),
      `${name} downloads before commands`,
    );
    assert.equal(action(job, "actions/upload-artifact").length, 1, `${name} one artifact output`);
    assert.deepEqual(action(job, "actions/upload-artifact")[0].with, spec.upload, `${name} bounded artifact output`);
    assert.equal(job.steps.at(-1), action(job, "actions/upload-artifact")[0], `${name} uploads after all gates`);
  }
  assert.deepEqual(
    action(workflow.jobs.java, "actions/setup-java")[0].with,
    { distribution: "temurin", "java-version": "26", cache: "maven" },
    "Java 26 build",
  );
  assert.deepEqual(
    publish.steps.filter((step) => step.run).map((step) => step.run),
    ["node scripts/demo/check-release.mjs publish release-contexts"],
    "artifact-only publication, no deployment",
  );
  assert.equal(
    action(publish, "docker/setup-buildx-action").length,
    1,
    "explicit Buildx",
  );
  assert.equal(
    action(publish, "actions/download-artifact").length,
    1,
    "download verified artifacts",
  );
  assert.deepEqual(
    action(publish, "actions/download-artifact")[0].with,
    { name: "demo-contexts", path: "release-contexts" },
    "matching artifact transfer",
  );
  assert.equal(
    action(publish, "docker/login-action").length,
    1,
    "one scoped registry login",
  );
  assert.deepEqual(
    action(publish, "docker/login-action")[0].with,
    {
      registry: "ghcr.io",
      username: "${{ github.actor }}",
      password: "${{ secrets.GITHUB_TOKEN }}",
    },
    "repository token only",
  );
  assert.equal(
    publish.env,
    undefined,
    "source provenance comes from committed snapshot metadata",
  );
  assert.equal(
    action(publish, "actions/upload-artifact").length,
    1,
    "one completed release manifest",
  );
  assert.deepEqual(
    action(publish, "actions/upload-artifact")[0].with,
    {
      name: "demo-release-manifest",
      path: "release-manifest.json",
      "if-no-files-found": "error",
      "retention-days": 30,
    },
    "complete manifest artifact",
  );
  assert.equal(
    publish.steps.at(-1),
    action(publish, "actions/upload-artifact")[0],
    "manifest only after publication succeeds",
  );
  assert.equal(
    publish.steps.findIndex((step) => step.run) >
      publish.steps.indexOf(action(publish, "actions/download-artifact")[0]),
    true,
    "download before publication",
  );
  for (const [kind, recipe] of Object.entries(dockerfiles)) {
    assert(
      recipe
        .match(/^FROM (.+)$/m)?.[1]
        .startsWith(baseFamilies[kind] + "@sha256:"),
      "reviewed official runtime family",
    );
    assert(
      pinned.test(recipe.match(/^FROM (.+)$/m)?.[1] ?? ""),
      "pinned runtime base",
    );
    assert(
      !/mvnw?|npm|COPY\s+\.\s|AS\s+build/i.test(recipe),
      "runtime recipe never compiles or copies the repository",
    );
  }
  assert.match(dockerfiles.runtime, /COPY app\.jar app\.jar/);
  assert.match(dockerfiles.runtime, /USER 10001:10001/);
  assert.match(dockerfiles.runtime, /\["java", "-jar", "\/app\/app.jar"\]/);
  return true;
}

function files(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    assert(!lstatSync(path).isSymbolicLink(), "no context symlinks");
    if (entry.isDirectory()) result.push(...files(path));
    else {
      assert(entry.isFile(), "regular artifact files only");
      result.push(path);
    }
  }
  return result;
}

export function publicConfig(value) {
  assert.deepEqual(
    Object.keys(value).sort(),
    ["apiBaseUrl", "cognito", "demoCheckoutEnabled", "wsUrl"],
    "public config fields only",
  );
  assert.deepEqual(
    Object.keys(value.cognito).sort(),
    ["region", "userPoolClientId", "userPoolId"],
    "secretless Cognito config",
  );
  assert.equal(value.apiBaseUrl, "https://api.rebyte.tech");
  assert.equal(value.wsUrl, "wss://api.rebyte.tech/ws");
  assert.equal(value.demoCheckoutEnabled, true);
  assert.equal(value.cognito.region, "eu-south-1");
  assert.equal(value.cognito.userPoolId, "eu-south-1_tG4BJNs2r");
  assert.equal(value.cognito.userPoolClientId, "1fvpct0cb1ui35rhmp54qesrrf");
}

export function validateContexts(contexts) {
  assert(
    lstatSync(contexts).isDirectory() && !lstatSync(contexts).isSymbolicLink(),
    "real context directory",
  );
  assert.deepEqual(
    readdirSync(contexts).sort(),
    [...IMAGE_NAMES].sort(),
    "complete image context set",
  );
  for (const name of IMAGE_NAMES) {
    const directory = join(contexts, name);
    if(name==='demo-runner'){validateRunnerContext(directory);continue;}
    assert(
      lstatSync(directory).isDirectory() &&
        !lstatSync(directory).isSymbolicLink(),
      "no linked image context",
    );
    const expected =
      name === "web"
        ? ["Dockerfile", "browser", "config.demo.json", "nginx.demo.conf"]
        : name === "rabbitmq"
          ? ["Dockerfile"]
          : ["Dockerfile", "app.jar"];
    assert.deepEqual(
      readdirSync(directory).sort(),
      expected.sort(),
      "artifact-only context file allowlist",
    );
    const recipe = read(join(directory, "Dockerfile"));
    const family =
      name === "web"
        ? baseFamilies.web
        : name === "rabbitmq"
          ? baseFamilies.rabbitmq
          : baseFamilies.runtime;
    assert(
      recipe.match(/^FROM (.+)$/m)?.[1].startsWith(family + "@sha256:"),
      "reviewed staged runtime family",
    );
    assert(
      pinned.test(recipe.match(/^FROM (.+)$/m)?.[1] ?? ""),
      "pinned staged base",
    );
    if (name === "rabbitmq")
      assert.match(recipe, /ADD --checksum=sha256:[a-f0-9]{64} --chmod=644/);
    else if (name !== "web") {
      const jar = readFileSync(join(directory, "app.jar"));
      assert(
        jar.subarray(0, 4).equals(Buffer.from([0x50, 0x4b, 0x03, 0x04])) &&
          jar.includes(Buffer.from("BOOT-INF/")),
        "nonempty Spring Boot jar",
      );
      assert(!/mvnw?|npm|COPY\s+\.\s/i.test(recipe), "no source rebuild");
    }
    for (const file of files(directory)) {
      const local = file.slice(directory.length + 1).replaceAll("\\", "/");
      assert(
        !local
          .split("/")
          .some(
            (part) =>
              part.startsWith(".") ||
              /^(src|node_modules|reports?|credentials|auth.*\.json)$/i.test(
                part,
              ),
          ),
        "no private/source/report files",
      );
      if (name === "web" && local.startsWith("browser/")) {
        assert(
          /\.(js|mjs|html|css|json|png|jpe?g|webp|avif|svg|ico|woff2?|ttf|otf|txt|webmanifest)$/.test(
            local,
          ),
          "compiled public assets only",
        );
        assert(
          !["config.json", "config.demo.json"].includes(basename(file)),
          "config injected only by image recipe",
        );
        if (/\.(js|mjs|html|css|json|txt)$/.test(local))
          assert(
            !/eu-south-1_WPMqs7BW8|localhost:8080|\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----/.test(
              read(file),
            ),
            "no known development/private values",
          );
      }
    }
    if (name === "web") {
      assert(
        readFileSync(join(directory, "browser/index.html")).length > 0,
        "SPA index exists",
      );
      publicConfig(JSON.parse(read(join(directory, "config.demo.json"))));
    }
  }
  return true;
}

export function prepareContexts(root, contexts) {
  assert(!existsSync(contexts), "refuse stale or existing artifact contexts");
  // The smoke runs in a separate CI job, so its report is the only proof of which context it exercised.
  const proof = JSON.parse(
    read(join(root, "target/demo-runner-smoke/report.json")),
  ).recipeContextSha256;
  mkdirSync(contexts, { recursive: true });
  for (const name of IMAGE_NAMES) {
    const target = join(contexts, name);
    if(name==='demo-runner'){assert.equal(stageRunnerContext(root,target),proof,'runner smoke proved a different staged context');continue;}
    mkdirSync(target);
    if (name === "web") {
      const dist = join(root, "web/dist");
      const builds = readdirSync(dist).filter((entry) =>
        existsSync(join(dist, entry, "browser/index.html")),
      );
      assert.equal(builds.length, 1, "exactly one compiled browser build");
      cpSync(join(dist, builds[0], "browser"), join(target, "browser"), {
        recursive: true,
        filter: (path) =>
          !["config.json", "config.demo.json"].includes(basename(path)),
      });
      cpSync(
        join(root, "web/public/config.demo.json"),
        join(target, "config.demo.json"),
      );
      cpSync(
        join(root, "web/nginx.demo.conf"),
        join(target, "nginx.demo.conf"),
      );
      cpSync(join(root, "web/Dockerfile.demo"), join(target, "Dockerfile"));
    } else if (name === "rabbitmq") {
      const original = read(join(root, "infra/rabbitmq/Dockerfile"));
      assert.match(original, /^FROM rabbitmq:4\.1-management$/m);
      assert.match(original, /^ARG PLUGIN_VERSION=4\.1\.0$/m);
      const recipe = original
        .replace(
          "FROM rabbitmq:4.1-management",
          "FROM rabbitmq:4.1-management@sha256:789893c0b4907f0ff3bc6e630120174ab6879825907f4c3ab79439e2488409cc",
        )
        .replace(
          "ADD --chmod=644",
          "ADD --checksum=sha256:567f876378e70af9d949de4066bbb2fc30162b46bcbc9efe2430076b172e4a87 --chmod=644",
        );
      writeFileSync(join(target, "Dockerfile"), recipe);
    } else {
      const directory = join(root, name, "target");
      const jars = readdirSync(directory).filter(
        (file) => file.endsWith(".jar") && !/-(plain|tests)\.jar$/.test(file),
      );
      assert.equal(jars.length, 1, "exactly one executable jar per service");
      cpSync(join(directory, jars[0]), join(target, "app.jar"));
      cpSync(
        join(root, "infra/Dockerfile.runtime"),
        join(target, "Dockerfile"),
      );
    }
  }
  return validateContexts(contexts);
}

export function readSourceRevision(path) {
  const value = JSON.parse(read(path));
  assert.deepEqual(
    Object.keys(value),
    ["sourceGitlabSha"],
    "public source provenance fields only",
  );
  assert(
    typeof value.sourceGitlabSha === "string" &&
      /^[a-f0-9]{40}$/.test(value.sourceGitlabSha),
    "full source GitLab revision required",
  );
  return value.sourceGitlabSha;
}

const docker = (args) =>
  execFileSync("docker", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 16 * 1024 * 1024,
  });

export function publishRelease(
  contexts,
  repository,
  sourceGitlabSha,
  githubReleaseSha,
  run = docker,
) {
  assert(
    typeof repository === "string" &&
      typeof sourceGitlabSha === "string" &&
      typeof githubReleaseSha === "string",
    "text repository and revisions required",
  );
  assert(
    /^[a-z0-9][a-z0-9-]*\/[a-z0-9][a-z0-9._-]*$/.test(repository),
    "valid lowercase GitHub repository",
  );
  assert(
    /^[a-f0-9]{40}$/.test(sourceGitlabSha ?? "") &&
      /^[a-f0-9]{40}$/.test(githubReleaseSha ?? "") &&
      sourceGitlabSha !== githubReleaseSha,
    "distinct source GitLab and GitHub release revisions",
  );
  validateContexts(contexts);
  const manifestFile = join(dirname(contexts), "release-manifest.json");
  assert(!existsSync(manifestFile), "refuse a stale release manifest");
  const metadata = join(dirname(contexts), "release-metadata");
  mkdirSync(metadata, { recursive: true });
  const images = {};
  for (const name of IMAGE_NAMES) {
    const directory = join(contexts, name);
    const tag = `ghcr.io/${repository}/${name}:${githubReleaseSha}`;
    const record = join(metadata, `${name}.json`);
    run([
      "buildx",
      "build",
      "--platform",
      "linux/amd64",
      "--provenance=false",
      "--file",
      join(directory, "Dockerfile"),
      "--tag",
      tag,
      "--label",
      `org.opencontainers.image.source=https://github.com/${repository}`,
      "--label",
      `org.opencontainers.image.revision=${githubReleaseSha}`,
      "--metadata-file",
      record,
      "--push",
      directory,
    ]);
    const digest = JSON.parse(read(record))["containerimage.digest"];
    assert(
      /^sha256:[a-f0-9]{64}$/.test(digest ?? ""),
      "verified published image digest",
    );
    images[name] = { tag, digest };
  }
  for (const [name, image] of Object.entries(images)) {
    run([
      "buildx",
      "imagetools",
      "create",
      "--tag",
      `ghcr.io/${repository}/${name}:demo`,
      `ghcr.io/${repository}/${name}@${image.digest}`,
    ]);
  }
  const manifest = {
    sourceGitlabSha,
    githubReleaseSha,
    platform: "linux/amd64",
    images,
  };
  writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + "\n");
  return manifest;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    // YAML's JSON syntax keeps workflow inspection free of a parser dependency.
    const workflow = JSON.parse(
      read(join(sourceRoot, ".github/workflows/demo-images.yml")),
    );
    validateWorkflow(workflow, {
      runtime: read(join(sourceRoot, "infra/Dockerfile.runtime")),
      web: read(join(sourceRoot, "web/Dockerfile.demo")),
    });
    const [command, target] = process.argv.slice(2);
    const sourceGitlabSha =
      process.env.GITHUB_ACTIONS === "true"
        ? readSourceRevision(join(sourceRoot, "demo-source.json"))
        : undefined;
    if (sourceGitlabSha) {
      assert(
        /^[a-f0-9]{40}$/.test(process.env.GITHUB_SHA ?? "") &&
          sourceGitlabSha !== process.env.GITHUB_SHA,
        "distinct snapshot source and GitHub release revisions",
      );
    }
    if (command === "prepare") {
      assert(target, "artifact destination required");
      prepareContexts(sourceRoot, resolve(target));
      console.log("Ten artifact-only image contexts validated.");
    } else if (command === "publish") {
      assert.equal(
        process.env.GITHUB_ACTIONS,
        "true",
        "publication runs only inside the approved GitHub workflow",
      );
      assert(target, "downloaded artifact path required");
      publishRelease(
        resolve(target),
        process.env.GITHUB_REPOSITORY?.toLowerCase(),
        sourceGitlabSha,
        process.env.GITHUB_SHA,
      );
      console.log(
        "Ten SHA images published, demo tags promoted, release manifest written.",
      );
    } else {
      assert.equal(command, undefined, "unknown release command");
      console.log(
        "Release workflow and runtime recipes validated; artifact and publication gates run separately.",
      );
    }
  } catch (error) {
    console.error(`Release refused: ${error.message}`);
    process.exitCode = 1;
  }
}
