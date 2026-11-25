import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import { canonicalizeHost, isAlwaysBlockedLiteral, isForbiddenHostName } from './network-policy.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const SCHEMA_DIR = path.resolve(__dirname, '../../../docs/specs/plugin-schema');

const ajv = new Ajv2020({ allErrors: true, strict: false });

const manifestSchema = JSON.parse(fs.readFileSync(path.join(SCHEMA_DIR, 'manifest.schema.json'), 'utf8'));
const protocolSchema = JSON.parse(fs.readFileSync(path.join(SCHEMA_DIR, 'protocol-types.schema.json'), 'utf8'));
const repositorySchema = JSON.parse(fs.readFileSync(path.join(SCHEMA_DIR, 'repository.schema.json'), 'utf8'));

const validateManifestFn = ajv.compile(manifestSchema);
const validateRepoFn = ajv.compile(repositorySchema);
ajv.addSchema(protocolSchema, 'protocol-types');

const protocolValidators = new Map();

function formatAjvErrors(errors) {
  if (!errors || errors.length === 0) return [];
  return errors.map((e) => {
    const loc = e.instancePath ? `$${e.instancePath.replace(/\//g, '.')}` : '$';
    return `${loc}: ${e.message}`;
  });
}

export function validateManifest(manifest) {
  const valid = validateManifestFn(manifest);
  const errors = valid ? [] : formatAjvErrors(validateManifestFn.errors);
  if (!manifest || typeof manifest !== 'object') {
    return { valid: false, errors: errors.length ? errors : ['$: manifest must be an object'] };
  }

  // Semantic checks matching Android ManifestParser.kt (§11.3, §11.4)
  const hosts = manifest.permissions?.hosts;
  if (Array.isArray(hosts)) {
    for (const h of hosts) {
      if (typeof h !== 'string') continue;
      const bare = canonicalizeHost(h.replace(/^\*\./, ''));
      if (isForbiddenHostName(bare) || isAlwaysBlockedLiteral(bare)) {
        errors.push(`$.permissions.hosts: forbidden loopback/reserved host '${h}'`);
      }
    }
  }

  if (Array.isArray(manifest.config)) {
    const seen = new Set();
    manifest.config.forEach((f, idx) => {
      if (!f || typeof f !== 'object') return;
      if (typeof f.key === 'string') {
        if (seen.has(f.key)) {
          errors.push(`$.config[${idx}]: duplicate config key '${f.key}'`);
        }
        seen.add(f.key);
      }
      if (f.default !== undefined && f.default !== null) {
        if ((f.type === 'text' || f.type === 'password' || f.type === 'url') && typeof f.default !== 'string') {
          errors.push(`$.config[${idx}].default must be a string for type '${f.type}'`);
        } else if (f.type === 'number' && typeof f.default !== 'number') {
          errors.push(`$.config[${idx}].default must be a number for type 'number'`);
        } else if (f.type === 'toggle' && typeof f.default !== 'boolean') {
          errors.push(`$.config[${idx}].default must be a boolean for type 'toggle'`);
        } else if (f.type === 'select') {
          const ok = typeof f.default === 'string' && Array.isArray(f.options) &&
            f.options.some((o) => o && o.value === f.default);
          if (!ok) {
            errors.push(`$.config[${idx}].default must match one of options[].value`);
          }
        }
      }
    });
  }

  return { valid: errors.length === 0, errors };
}

export function validateProtocolType(defName, value) {
  let fn = protocolValidators.get(defName);
  if (!fn) {
    fn = ajv.getSchema(`protocol-types#/$defs/${defName}`);
    if (!fn) throw new Error(`Unknown protocol type definition: ${defName}`);
    protocolValidators.set(defName, fn);
  }
  const valid = fn(value);
  return { valid: Boolean(valid), errors: valid ? [] : formatAjvErrors(fn.errors) };
}

export function validateRepository(indexObj) {
  const valid = validateRepoFn(indexObj);
  return { valid: Boolean(valid), errors: valid ? [] : formatAjvErrors(validateRepoFn.errors) };
}
