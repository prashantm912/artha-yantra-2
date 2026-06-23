# Parked Angular e2e journeys (React cutover)

These Playwright journeys were written against the **Angular `frontend-ui`** DOM
(`ay-*` component selectors, PrimeNG classes). At the React cutover the gateway's
catch-all ingress switched to serve `frontend-react`, so these specs no longer match
the live DOM and were moved out of the run (`testDir: ./tests`).

They are **preserved, not deleted** — each is a real user journey worth porting to the
React DOM (`getByTestId` / `getByRole`) when the React e2e suite is fleshed out. The
cutover keeps a single deployed smoke (`../tests/smoke.spec.ts`: stack boots → React
serves through the gateway → form login → authed page renders → API answers). The
React inner-loop e2e (login + options-chain, dev-server `:4300`) lives under
`frontend-react/e2e/`.

To revive one: port its selectors to the React components, fix the `./helpers` import
path, and move it back under `e2e/tests/`.
