// Monaco base editor worker entry — bundled as a separate worker chunk by esbuild and referenced
// via `new Worker(new URL('./workers/editor.worker', import.meta.url))` (a relative URL, which
// esbuild resolves; a bare `monaco-editor/...` specifier inside new URL would NOT resolve).
import 'monaco-editor/esm/vs/editor/editor.worker.js';
