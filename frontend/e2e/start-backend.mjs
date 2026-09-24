// Starts an isolated backend for E2E: recreates the `enterprise_admin_e2e` schema on the local Docker MySQL,
// provisions least-privilege migration/runtime users with the repository's provisioning script, then runs the
// Spring Boot app (Java 21) against it on port 8082. Nothing touches the development schema.
//
// Needs the MySQL root password: E2E_MYSQL_ROOT_PASSWORD, or MYSQL_ROOT_PASSWORD from the git-ignored root .env.
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const repo = new URL('../..', import.meta.url).pathname;
const container = process.env.E2E_MYSQL_CONTAINER ?? 'enterprise-admin-mysql';
const schema = 'enterprise_admin_e2e';
const port = process.env.E2E_API_PORT ?? '8082';

function dotenv(key) {
  const file = join(repo, '.env');
  if (!existsSync(file)) return undefined;
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const i = line.indexOf('=');
    if (i > 0 && line.slice(0, i).trim() === key) {
      let value = line.slice(i + 1).trim();
      if (/^(['"]).*\1$/.test(value)) value = value.slice(1, -1);
      return value;
    }
  }
  return undefined;
}

const rootPassword = process.env.E2E_MYSQL_ROOT_PASSWORD ?? dotenv('MYSQL_ROOT_PASSWORD');
if (!rootPassword) {
  console.error('E2E needs E2E_MYSQL_ROOT_PASSWORD (or MYSQL_ROOT_PASSWORD in the root .env).');
  process.exit(1);
}
const migrator = { user: 'ea_e2e_migrator', password: randomBytes(18).toString('hex') };
const runtime = { user: 'ea_e2e_app', password: randomBytes(18).toString('hex') };

const provision = readFileSync(join(repo, 'docker/mysql/provision-users.sql'), 'utf8')
  .replaceAll('${SCHEMA}', schema).replaceAll('${HOST}', '%')
  .replaceAll('${MIGRATION_USER}', migrator.user).replaceAll('${MIGRATION_PASSWORD}', migrator.password)
  .replaceAll('${RUNTIME_USER}', runtime.user).replaceAll('${RUNTIME_PASSWORD}', runtime.password);
const sql = `DROP DATABASE IF EXISTS \`${schema}\`;
DROP USER IF EXISTS '${migrator.user}'@'%', '${runtime.user}'@'%';
CREATE DATABASE \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
${provision}`;
execFileSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD', container, 'mysql', '-u', 'root'], {
  input: sql,
  env: { ...process.env, MYSQL_PWD: rootPassword },
  stdio: ['pipe', 'inherit', 'inherit'],
});
console.log(`[e2e] schema ${schema} recreated; least-privilege users provisioned`);

// Java 21: JAVA_HOME_21, else macOS's java_home lookup, else the JAVA_HOME already set (CI: actions/setup-java).
const javaHome = process.env.JAVA_HOME_21
  ?? (process.platform === 'darwin' ? execFileSync('/usr/libexec/java_home', ['-v', '21']).toString().trim() : process.env.JAVA_HOME);
if (!javaHome) {
  console.error('E2E needs Java 21: set JAVA_HOME_21 or JAVA_HOME.');
  process.exit(1);
}
const mysqlPort = process.env.MYSQL_PORT ?? dotenv('MYSQL_PORT') ?? '3307';
const env = {
  ...process.env,
  JAVA_HOME: javaHome,
  SERVER_PORT: port,
  DB_URL: `jdbc:mysql://127.0.0.1:${mysqlPort}/${schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`,
  DB_USERNAME: runtime.user,
  DB_PASSWORD: runtime.password,
  FLYWAY_USER: migrator.user,
  FLYWAY_PASSWORD: migrator.password,
  DB_RUNTIME_USER: runtime.user,
  DB_VERIFY_LEAST_PRIVILEGE: 'true',
  JWT_SECRET: randomBytes(48).toString('base64'),
  ADMIN_EMAIL: 'e2e.admin@enterprise-admin.test',
  ADMIN_PASSWORD: process.env.E2E_ADMIN_PASSWORD ?? 'E2e-Admin-Password-2026',
  ADMIN_FIRST_NAME: 'E2E',
  ADMIN_LAST_NAME: 'Administrator',
  REFRESH_COOKIE_SECURE: 'false',
  ALLOWED_ORIGINS: '',
  // The whole suite signs in from 127.0.0.1: raise the per-IP limits (per-account limits stay at their defaults).
  SPRING_APPLICATION_JSON: JSON.stringify({ app: { security: { 'rate-limit': { policies: {
    'login-ip': { limit: 2000, window: '1m' }, 'refresh-ip': { limit: 2000, window: '1m' },
    'admin-mutation': { limit: 2000, window: '1m' }, 'account-mutation': { limit: 2000, window: '1m' },
    'profile-update': { limit: 2000, window: '1m' } } } } } }),
};
const child = spawn('./mvnw', ['-q', 'spring-boot:run', '-Dspring-boot.run.profiles=e2e'], {
  cwd: join(repo, 'backend'), env, stdio: 'inherit',
});
const stop = () => child.kill('SIGTERM');
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
child.on('exit', (code) => process.exit(code ?? 0));
