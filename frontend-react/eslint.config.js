import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      // axe's scrollable-region-focusable (wcag 2.1.1) REQUIRES a focusable scroll container, which
      // this rule otherwise forbids — allow tabIndex on region/group/tabpanel (the dense-table pattern).
      'jsx-a11y/no-noninteractive-tabindex': [
        'error',
        { tags: [], roles: ['tabpanel', 'region', 'group'], allowExpressionValues: true },
      ],
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // D1 typing bar — no `any`.
      '@typescript-eslint/no-explicit-any': 'error',
      // E-9 chart containment: lightweight-charts only inside the chart wrappers (master plan §20).
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'lightweight-charts',
              message:
                'Import lightweight-charts only inside src/components/charts/** (E-9 containment).',
            },
          ],
        },
      ],
    },
  },
  // Chart wrappers are the one place lightweight-charts may be imported.
  {
    files: ['src/components/charts/**/*.{ts,tsx}'],
    rules: { 'no-restricted-imports': 'off' },
  },
);
