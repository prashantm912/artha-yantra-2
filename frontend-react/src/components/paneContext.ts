import { createContext } from 'react';

/**
 * True inside a Multiple-Window pane (audit §9.6): embedded pages collapse their hero PageHeader to
 * a visually-hidden heading (the pane chrome already names the widget), doubling the pane's data
 * density. Lives outside PageHeader.tsx so that file stays component-only (react-refresh).
 */
export const CompactPaneContext = createContext(false);
