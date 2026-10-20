import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  cpSync,
  rmSync,
  symlinkSync,
} from "node:fs";
import { basename, dirname, join, matchesGlob, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import {
  IMAGE_NAMES,
  prepareContexts,
  validateContexts,
  validateWorkflow,
  publishRelease,
  readSourceRevision,
} from "./check-release.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const workflow = () =>
  JSON.parse(
    readFileSync(join(root, ".github/workflows/demo-images.yml"), "utf8"),
  );
const dockerfiles = () => ({
  runtime: readFileSync(join(root, "infra/Dockerfile.runtime"), "utf8"),
  web: readFileSync(join(root, "web/Dockerfile.demo"), "utf8"),
});

function workspace(t) {
  const temp = mkdtempSync(join(tmpdir(), "rebyte-release-test-"));
  t.after(() => {
    assert.equal(dirname(resolve(temp)), resolve(tmpdir()));
    assert(basename(temp).startsWith("rebyte-release-test-"));
    rmSync(temp, { recursive: true, force: true });
  });
  for (const path of [
    "infra/rabbitmq",
    "web/public",
    "web/dist/rebyte-web/browser",
  ])
    mkdirSync(join(temp, path), { recursive: true });
  for (const file of [
    "infra/Dockerfile.runtime",
    "infra/Dockerfile.demo-runner",
    "infra/rabbitmq/Dockerfile",
    "web/Dockerfile.demo",
    "web/nginx.demo.conf",
    "web/public/config.demo.json",
  ])
    cpSync(join(root, file), join(temp, file));
  mkdirSync(join(temp,'scripts'),{recursive:true});
  cpSync(join(root,'scripts/demo'),join(temp,'scripts/demo'),{recursive:true});
  writeFileSync(
    join(temp, "web/dist/rebyte-web/browser/index.html"),
    "<html>fixture</html>",
  );
  writeFileSync(
    join(temp, "web/dist/rebyte-web/browser/config.json"),
    '{"apiBaseUrl":"http://localhost:8080"}',
  );
  for (const service of IMAGE_NAMES.filter(
    (name) => !["web", "rabbitmq", "demo-runner"].includes(name),
  )) {
    mkdirSync(join(temp, service, "target"), { recursive: true });
    writeFileSync(
      join(temp, service, "target", `${service}-test.jar`),
      Buffer.concat([
        Buffer.from([0x50, 0x4b, 0x03, 0x04]),
        Buffer.from("BOOT-INF/classes/"),
      ]),
    );
  }
  return temp;
}

test("actual workflow and runtime recipes satisfy the release policy", () => {
  assert.equal(validateWorkflow(workflow(), dockerfiles()), true);
  const reactorModules = [
    ...readFileSync(join(root, "pom.xml"), "utf8").matchAll(
      /<module>([^<]+)<\/module>/g,
    ),
  ]
    .map((match) => match[1])
    .filter((name) => name !== "it.rebyte.commons");
  assert.deepEqual(
    [...IMAGE_NAMES].sort(),
    [...reactorModules, "web", "rabbitmq", "demo-runner"].sort(),
  );
});

test('runner artifacts reject private configuration and changed executable bytes',t=>{
  for(const tamper of ['private-config','changed-code']){
    const temp=workspace(t),contexts=join(temp,'contexts');prepareContexts(temp,contexts);
    const directory=join(contexts,'demo-runner','scripts/demo');
    if(tamper==='private-config')writeFileSync(join(directory,'config.json'),'{"private":"fixture"}');
    else writeFileSync(join(directory,'reset.mjs'),'throw Error("changed runtime");');
    assert.throws(()=>validateContexts(contexts));
  }
});

test('RabbitMQ fixture build is mandatory before tooling tests',()=>{
  const value=workflow();value.jobs.build.steps=value.jobs.build.steps.filter(step=>step.run!=='docker build --tag rebyte/rabbitmq:4.1-delayed infra/rabbitmq');
  assert.throws(()=>validateWorkflow(value,dockerfiles()));
});

test('runner behavioral smoke is mandatory in the verified build job',()=>{
  const value=workflow();value.jobs.build.steps=value.jobs.build.steps.filter(step=>step.run!=='node scripts/demo/runner-image-smoke.mjs');
  assert.throws(()=>validateWorkflow(value,dockerfiles()));
});

test("CLI refuses publication outside GitHub Actions", (t) => {
  const temp = workspace(t);
  const result = spawnSync(
    process.execPath,
    [
      join(root, "scripts/demo/check-release.mjs"),
      "publish",
      join(temp, "unused-contexts"),
    ],
    {
      env: { ...process.env, GITHUB_ACTIONS: "false" },
      encoding: "utf8",
    },
  );
  assert.equal(result.status, 1);
  assert.match(
    result.stderr,
    /publication runs only inside the approved GitHub workflow/,
  );
});

test("source provenance is fixed in the exported snapshot and contains only a full revision", (t) => {
  const temp = workspace(t);
  const path = join(temp, "demo-source.json");
  writeFileSync(path, JSON.stringify({ sourceGitlabSha: "a".repeat(40) }));
  assert.equal(readSourceRevision(path), "a".repeat(40));
  for (const value of [
    { sourceGitlabSha: "short" },
    { sourceGitlabSha: ["a".repeat(40)] },
    { sourceGitlabSha: "a".repeat(40), token: "fixture" },
    {},
  ]) {
    writeFileSync(path, JSON.stringify(value));
    assert.throws(() => readSourceRevision(path));
  }
});

for (const [name, mutate] of [
  [
    "duplicate reactor build",
    (value) => value.jobs.publish.steps.unshift({ run: "./mvnw -B verify" }),
  ],
  [
    "skipped backend tests",
    (value) =>
      (value.jobs.build.steps.find(
        (step) => step.run === "./mvnw -B verify",
      ).run += " -DskipTests"),
  ],
  [
    "matrix rebuild",
    (value) =>
      (value.jobs.build.strategy = { matrix: { service: ["api-gateway"] } }),
  ],
  [
    "implicit Git build context",
    (value) =>
      value.jobs.publish.steps.push({
        run: "docker build https://github.com/example/repo.git",
      }),
  ],
  [
    "deployment webhook",
    (value) =>
      value.jobs.publish.steps.push({
        run: "curl https://coolify.example.test/deploy",
      }),
  ],
  [
    "ignored failures",
    (value) =>
      (value.jobs.build.steps.find((step) => step.run === "./mvnw -B verify")[
        "continue-on-error"
      ] = true),
  ],
  [
    "unreviewed action tag",
    (value) => (value.jobs.build.steps[0].uses = "actions/checkout@main"),
  ],
  [
    "source changes ignored",
    (value) => value.on.push["paths-ignore"].push("**"),
  ],
  [
    "untrusted pull request publication",
    (value) => (value.on.pull_request_target = {}),
  ],
  [
    "missing artifact download",
    (value) =>
      (value.jobs.publish.steps = value.jobs.publish.steps.filter(
        (step) => !step.uses?.startsWith("actions/download-artifact@"),
      )),
  ],
  ["missing build dependency", (value) => (value.jobs.publish.needs = [])],
  [
    "build has registry credentials",
    (value) =>
      (value.jobs.build.permissions = { contents: "read", packages: "write" }),
  ],
  [
    "cancellation during promotion",
    (value) => (value.concurrency["cancel-in-progress"] = true),
  ],
  [
    "environment skips tests",
    (value) =>
      (value.jobs.build.env = { MAVEN_OPTS: "-Dmaven.test.skip=true" }),
  ],
  [
    "step environment skips tests",
    (value) =>
      (value.jobs.build.steps.find(
        (step) => step.run === "./mvnw -B verify",
      ).env = { MAVEN_OPTS: "-Dmaven.test.skip=true" }),
  ],
]) {
  test(`workflow refuses ${name}`, () => {
    const value = workflow();
    mutate(value);
    assert.throws(() => validateWorkflow(value, dockerfiles()));
  });
}

test("docs-only pushes skip, mixed code/docs pushes build", () => {
  const ignores = workflow().on.push["paths-ignore"];
  const runs = (files) =>
    files.some(
      (file) => !ignores.some((pattern) => matchesGlob(file, pattern)),
    );
  assert.equal(
    runs(["README.md", "docs/architecture.md", ".planning/STATE.md"]),
    false,
  );
  assert.equal(runs(["docs/architecture.md", "api-gateway/pom.xml"]), true);
  assert.equal(runs([".github/workflows/demo-images.yml"]), true);
});

test("runtime recipes cannot copy source or rebuild Java", () => {
  for (const runtime of [
    dockerfiles().runtime.replace("COPY app.jar app.jar", "COPY . ."),
    dockerfiles().runtime + "\nRUN ./mvnw verify\n",
  ]) {
    assert.throws(() =>
      validateWorkflow(workflow(), { ...dockerfiles(), runtime }),
    );
  }
});

test("a pinned digest cannot substitute an unreviewed runtime family", () => {
  const recipes = dockerfiles();
  assert.throws(() =>
    validateWorkflow(workflow(), {
      ...recipes,
      runtime: recipes.runtime.replace(
        "eclipse-temurin:26-jre-noble",
        "unreviewed/runtime",
      ),
    }),
  );
});

test("preparation stages exactly nine isolated contexts and replaces local SPA config", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  assert.equal(validateContexts(contexts), true);
  assert.throws(() => readFileSync(join(contexts, "web/browser/config.json")));
  assert.match(
    readFileSync(join(contexts, "rabbitmq/Dockerfile"), "utf8"),
    /ADD --checksum=sha256:/,
  );
  assert.match(
    readFileSync(join(contexts, "rabbitmq/Dockerfile"), "utf8"),
    /FROM rabbitmq:4\.1-management@sha256:/,
  );
});

test("missing, ambiguous and empty jars refuse preparation", (t) => {
  for (const kind of ["missing", "ambiguous", "empty"]) {
    const temp = workspace(t);
    const jar = join(temp, "api-gateway/target/api-gateway-test.jar");
    if (kind === "missing") rmSync(jar);
    if (kind === "empty") writeFileSync(jar, "");
    if (kind === "ambiguous")
      cpSync(jar, join(temp, "api-gateway/target/another.jar"));
    assert.throws(() => prepareContexts(temp, join(temp, "contexts")));
  }
});

for (const file of [
  "api-gateway/.env",
  "api-gateway/auth.json",
  "web/credentials.json",
  "web/browser/src/main.ts",
  "web/browser/auth-state.json",
]) {
  test(`context rejects private or source file ${file}`, (t) => {
    const temp = workspace(t);
    const contexts = join(temp, "contexts");
    prepareContexts(temp, contexts);
    const target = join(contexts, file);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, "private fixture");
    assert.throws(() => validateContexts(contexts));
  });
}

test("public config cannot contain a client secret", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  const config = join(contexts, "web/config.demo.json");
  const value = JSON.parse(readFileSync(config, "utf8"));
  value.cognito.clientSecret = "fixture-only";
  writeFileSync(config, JSON.stringify(value));
  assert.throws(() => validateContexts(contexts));
});

function fakeDocker(failAt = 0, invalidDigest = false) {
  const calls = [];
  const run = (args) => {
    calls.push(args);
    if (args[1] === "build") {
      const count = calls.filter((call) => call[1] === "build").length;
      if (count === failAt) throw new Error("fixture build failure");
      writeFileSync(
        args[args.indexOf("--metadata-file") + 1],
        JSON.stringify({
          "containerimage.digest": invalidDigest
            ? "invalid"
          : `sha256:${count.toString(16).repeat(64)}`,
        }),
      );
    }
    return "";
  };
  return { calls, run };
}

test("all SHA images precede demo tags and manifest retains different source/release revisions", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  const fake = fakeDocker();
  const result = publishRelease(
    contexts,
    "owner/rebyte-demo",
    "a".repeat(40),
    "b".repeat(40),
    fake.run,
  );
  assert.equal(fake.calls.length, IMAGE_NAMES.length*2);
  assert(fake.calls.slice(0, IMAGE_NAMES.length).every((call) => call[1] === "build"));
  assert(fake.calls.slice(IMAGE_NAMES.length).every((call) => call[1] === "imagetools"));
  assert.equal(result.sourceGitlabSha, "a".repeat(40));
  assert.equal(result.githubReleaseSha, "b".repeat(40));
  assert.deepEqual(Object.keys(result.images), IMAGE_NAMES);
  for (const call of fake.calls.slice(0, IMAGE_NAMES.length)) {
    assert.equal(call[call.indexOf("--platform") + 1], "linux/amd64");
    assert(call.includes("--push"));
    assert(
      call.includes(
        "org.opencontainers.image.source=https://github.com/owner/rebyte-demo",
      ),
    );
    assert(call[call.indexOf("--tag") + 1].endsWith("b".repeat(40)));
  }
});

test("failed or incomplete SHA sets and invalid digests never promote demo tags", (t) => {
  for (const [failAt, invalidDigest] of [
    [1, false],
    [IMAGE_NAMES.length, false],
    [0, true],
  ]) {
    const temp = workspace(t);
    const contexts = join(temp, "contexts");
    prepareContexts(temp, contexts);
    const fake = fakeDocker(failAt, invalidDigest);
    assert.throws(() =>
      publishRelease(
        contexts,
        "owner/rebyte-demo",
        "a".repeat(40),
        "b".repeat(40),
        fake.run,
      ),
    );
    assert.equal(
      fake.calls.some((call) => call[1] === "imagetools"),
      false,
    );
    assert.throws(() => readFileSync(join(temp, "release-manifest.json")));
  }
});

test("missing contexts and invalid provenance fail before any registry operation", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  const fake = fakeDocker();
  for (const [repo, source, release] of [
    ["bad repo", "a".repeat(40), "b".repeat(40)],
    ["owner/repo", "", "b".repeat(40)],
    ["owner/repo", "a".repeat(40), "a".repeat(40)],
  ]) {
    assert.throws(() =>
      publishRelease(contexts, repo, source, release, fake.run),
    );
  }
  rmSync(join(contexts, "api-gateway/app.jar"));
  assert.throws(() =>
    publishRelease(
      contexts,
      "owner/repo",
      "a".repeat(40),
      "b".repeat(40),
      fake.run,
    ),
  );
  assert.equal(fake.calls.length, 0);
});

test("a linked context directory is refused", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  const link = join(temp, "linked-contexts");
  symlinkSync(
    contexts,
    link,
    process.platform === "win32" ? "junction" : "dir",
  );
  assert.throws(() => validateContexts(link));
});

test("failed tag promotion cannot produce a completed release manifest", (t) => {
  const temp = workspace(t);
  const contexts = join(temp, "contexts");
  prepareContexts(temp, contexts);
  const fake = fakeDocker();
  assert.throws(() =>
    publishRelease(
      contexts,
      "owner/repo",
      "a".repeat(40),
      "b".repeat(40),
      (args) => {
        if (args[1] === "imagetools")
          throw new Error("fixture promotion failure");
        return fake.run(args);
      },
    ),
  );
  assert.throws(() => readFileSync(join(temp, "release-manifest.json")));
});
