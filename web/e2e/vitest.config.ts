const { resolve } = require('node:path');
const { defineConfig } = require('vitest/config');

module.exports = defineConfig({
  root: resolve(__dirname, '..'),
  test: { environment: 'node', include: ['e2e/helpers/**/*.spec.ts'] },
});
