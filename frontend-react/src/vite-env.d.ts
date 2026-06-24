/// <reference types="vite/client" />

// @fontsource packages ship CSS only (no type declarations); these are side-effect imports
// resolved by Vite at build time. Declare them so `tsc -b` type-checking passes.
declare module '@fontsource-variable/*';
