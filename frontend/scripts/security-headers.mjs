// Generates the SPA security headers from security-headers.json (the single source of truth):
//  - recomputes the SHA-256 of the pre-paint boot script in src/index.html and writes it into the policy,
//  - writes angular.json → serve.options.headers (development server),
//  - writes nginx/security-headers.conf (production nginx; included by every HTML/static location).
// Usage: npm run security:headers      (check:csp verifies nothing has drifted)
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
export const sha = (text) => `'sha256-${createHash('sha256').update(text, 'utf8').digest('base64')}'`;
export const inlineScripts = (html) => [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);

export function load() {
  return JSON.parse(readFileSync(join(root, 'security-headers.json'), 'utf8'));
}

export function cspString(config) {
  return Object.entries(config.contentSecurityPolicy).map(([directive, sources]) => [directive, ...sources].join(' ')).join('; ');
}

export function allHeaders(config) {
  return { 'Content-Security-Policy': cspString(config), ...config.headers };
}

export function nginxConf(config) {
  const lines = Object.entries(allHeaders(config)).map(([name, value]) => `add_header ${name} "${value}" always;`);
  return ['# GENERATED from security-headers.json by `npm run security:headers` — do not edit by hand.',
    '# Included by every location that serves the SPA (nginx drops inherited add_header when a location adds its own).',
    ...lines, '# HSTS: only for HTTPS requests ($hsts_value is empty otherwise, and nginx then omits the header).',
    'add_header Strict-Transport-Security $hsts_value always;', ''].join('\n');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const config = load();
  const scripts = inlineScripts(readFileSync(join(root, 'src/index.html'), 'utf8'));
  config.contentSecurityPolicy['script-src'] = ["'self'", ...scripts.map(sha)];
  writeFileSync(join(root, 'security-headers.json'), JSON.stringify(config, null, 2) + '\n');

  const angularPath = join(root, 'angular.json');
  const angular = JSON.parse(readFileSync(angularPath, 'utf8'));
  angular.projects.frontend.architect.serve.options.headers = allHeaders(config);
  writeFileSync(angularPath, JSON.stringify(angular, null, 2) + '\n');

  writeFileSync(join(root, 'nginx/security-headers.conf'), nginxConf(config));
  console.log('Security headers regenerated (angular.json, nginx/security-headers.conf).');
}
