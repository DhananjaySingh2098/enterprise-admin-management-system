// Verifies the SPA's Content-Security-Policy and security headers against security-headers.json, the single source
// of truth (see scripts/security-headers.mjs). Fails when:
//  - the policy contains 'unsafe-inline', 'unsafe-eval', wildcards or scheme-wide sources, or lacks key directives;
//  - an inline <script> in src/index.html (the pre-paint theme boot script) is not allowed by its SHA-256;
//  - angular.json (dev server) or nginx/security-headers.conf (production) drifted from the source of truth, or an
//    nginx location serving the SPA does not include the headers;
//  - the production build (if present) has inline <style>, inline event handlers, unhashed scripts or source maps;
//  - components declare styles/styleUrl or templates use static style="" attributes.
// Usage: npm run check:csp   (after `ng build` to include the built output)
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { allHeaders, cspString, inlineScripts, load, nginxConf, sha } from './security-headers.mjs';

const root = new URL('..', import.meta.url).pathname;
const problems = [];
const config = load();
const csp = cspString(config);
const directives = config.contentSecurityPolicy;

for (const [directive, sources] of Object.entries(directives)) {
  for (const bad of ["'unsafe-inline'", "'unsafe-eval'", "'unsafe-hashes'", '*', 'http:', 'https:', "'strict-dynamic'"]) {
    if (sources.includes(bad)) problems.push(`CSP ${directive} contains ${bad}`);
  }
}
for (const required of ['default-src', 'script-src', 'style-src', 'object-src', 'base-uri', 'frame-ancestors', 'form-action']) {
  if (!directives[required]) problems.push(`CSP lacks ${required}`);
}
if (directives['object-src']?.join() !== "'none'") problems.push("object-src must be 'none'");
if (directives['frame-ancestors']?.join() !== "'none'") problems.push("frame-ancestors must be 'none'");

const source = readFileSync(join(root, 'src/index.html'), 'utf8');
for (const script of inlineScripts(source)) {
  if (!directives['script-src']?.includes(sha(script))) problems.push(`src/index.html inline script ${sha(script)} is not in script-src (run npm run security:headers)`);
}

const angular = JSON.parse(readFileSync(join(root, 'angular.json'), 'utf8'));
if (JSON.stringify(angular.projects.frontend.architect.serve.options.headers) !== JSON.stringify(allHeaders(config))) {
  problems.push('angular.json serve headers differ from security-headers.json (run npm run security:headers)');
}
const nginxHeaders = join(root, 'nginx/security-headers.conf');
if (!existsSync(nginxHeaders) || readFileSync(nginxHeaders, 'utf8') !== nginxConf(config)) {
  problems.push('nginx/security-headers.conf differs from security-headers.json (run npm run security:headers)');
}
const template = join(root, 'nginx/default.conf.template');
if (existsSync(template)) {
  const nginx = readFileSync(template, 'utf8');
  for (const block of nginx.matchAll(/location\s+([^{]+)\{([\s\S]*?)\n    \}/g)) {
    const [, where, body] = block;
    const servesSpa = /try_files|root |alias /.test(body) && !where.includes('/api');
    if (servesSpa && !body.includes('include /etc/nginx/snippets/security-headers.conf;')) {
      problems.push(`nginx location ${where.trim()} serves the SPA without the security headers`);
    }
  }
} else {
  problems.push('nginx/default.conf.template is missing');
}

const dist = join(root, 'dist/frontend/browser');
const built = join(dist, 'index.html');
if (existsSync(built)) {
  const html = readFileSync(built, 'utf8');
  for (const script of inlineScripts(html)) {
    if (!directives['script-src']?.includes(sha(script))) problems.push(`built index.html inline script ${sha(script)} is not in script-src`);
  }
  if (/<style[\s>]/.test(html)) problems.push('built index.html contains an inline <style> (disable critical-CSS inlining)');
  if (/\son[a-z]+=/i.test(html)) problems.push('built index.html contains an inline event handler');
  if (readdirSync(dist).some((f) => f.endsWith('.map'))) problems.push('production build contains source maps');
}

const walk = (dir) => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? walk(path) : [path];
});
for (const file of walk(join(root, 'src/app'))) {
  if (file.endsWith('.spec.ts')) continue;
  const text = readFileSync(file, 'utf8');
  if (file.endsWith('.ts') && /\b(styles:\s*[`[]|styleUrls?:)/.test(text)) problems.push(`${file.slice(root.length)} declares component styles`);
  if ((file.endsWith('.html') || file.endsWith('.ts')) && /\sstyle="/.test(text)) problems.push(`${file.slice(root.length)} has a static style attribute`);
}

if (problems.length) {
  console.error('CSP check failed:\n - ' + problems.join('\n - '));
  process.exit(1);
}
console.log(`CSP check passed (${existsSync(built) ? 'source, dev server, nginx and production build' : 'source, dev server and nginx'}).`);
console.log(`Content-Security-Policy: ${csp}`);
