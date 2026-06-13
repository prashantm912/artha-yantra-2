// monaco-yaml language worker entry — bundled as a separate worker chunk, referenced via a
// relative `new URL('./workers/yaml.worker', import.meta.url)` for the same reason as editor.worker.
import 'monaco-yaml/yaml.worker';
