/// <reference types="vite/client" />

// @fontsource packages ship CSS only (no type declarations); these are side-effect imports
// resolved by Vite at build time. Declare them so `tsc -b` type-checking passes.
declare module '@fontsource-variable/*';

// Large GeoJSON map assets (e.g. world.geo.json for the World Indices echarts map). Typed as
// `unknown` on purpose: resolveJsonModule is off, and inferring the full literal type of a ~1 MB
// coordinate file would balloon tsc. Vite bundles the real JSON at build; consumers cast as needed.
declare module '*.geo.json' {
  const value: unknown;
  export default value;
}
