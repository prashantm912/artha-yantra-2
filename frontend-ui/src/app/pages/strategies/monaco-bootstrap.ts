import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
import { configureMonacoYaml, type MonacoYaml } from 'monaco-yaml';

/**
 * Monaco bootstrap (D2: Monaco used DIRECTLY — no `ngx-monaco-editor` wrapper). Imports the core
 * editor API ONLY (`editor.api`, not `editor.main` with every bundled language) so the editor-route
 * chunk stays lean; monaco-yaml registers the YAML language + worker. Workers are bundled by
 * @angular/build's esbuild via `new Worker(new URL(...))`. Everything here is confined to the lazy
 * `/strategies/:id/edit` route — Monaco never reaches the initial bundle (E-6).
 */

let envConfigured = false;
let yamlHandle: MonacoYaml | undefined;

function configureEnvironment(): void {
  if (envConfigured) {
    return;
  }
  (globalThis as unknown as { MonacoEnvironment: monaco.Environment }).MonacoEnvironment = {
    getWorker(_workerId: string, label: string): Worker {
      if (label === 'yaml') {
        return new Worker(new URL('./workers/yaml.worker', import.meta.url), { type: 'module' });
      }
      return new Worker(new URL('./workers/editor.worker', import.meta.url), { type: 'module' });
    },
  };
  envConfigured = true;
}

/** The shared monaco-yaml handle (configured once); callers push the live schema via `update`. */
export function ensureMonacoYaml(): MonacoYaml {
  configureEnvironment();
  if (!yamlHandle) {
    yamlHandle = configureMonacoYaml(monaco, {
      enableSchemaRequest: false,
      validate: true,
      hover: true,
      completion: true,
      schemas: [],
    });
  }
  return yamlHandle;
}

export { monaco };
