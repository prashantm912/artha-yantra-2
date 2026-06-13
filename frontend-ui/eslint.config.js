// ESLint 9 flat config (C-2.27): no-explicit-any is an ERROR (D1 typing bar),
// template a11y rules on. Angular-ESLint 21 recommended sets.
// @ts-check
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = tseslint.config(
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'ay', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: ['ay', 'app'], style: 'kebab-case' },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {},
  },
  {
    // E-9 chart containment (A13): lightweight-charts may be imported ONLY by the designated
    // chart-wrapper components — the lazy /charts module PLUS the shared sparkline/equity-curve
    // wrappers (dashboard sparklines + Phase 38 equity curves legitimately import LWC outside
    // /charts; a naive "no LWC outside /charts" rule would be WRONG). CI-enforced.
    files: ['src/**/*.ts'],
    ignores: [
      'src/app/pages/charts/**/*.ts',
      'src/app/shared/sparkline.ts',
      'src/app/shared/equity-curve.ts',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'lightweight-charts',
              message:
                'lightweight-charts is confined to /charts + the shared sparkline/equity-curve wrappers (E-9 containment, A13).',
            },
          ],
          patterns: ['lightweight-charts/*'],
        },
      ],
    },
  },
);
