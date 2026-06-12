#!/usr/bin/env node
// stomp-probe (A.7b / Phase 8): login at the gateway, open the /ws STOMP
// bridge, subscribe to a tick topic, print N frames, exit 0. The Stage-A
// exit-gate "mock ticks observable end-to-end" check.
//
//   node e2e/tools/stomp-probe.mjs [topic] [frames]
//   env: ARTHA_BASE_URL (default http://127.0.0.1:8080)
//        ARTHA_OWNER_PASSWORD (default stage-a-owner)
import { Client } from "@stomp/stompjs";
import { WebSocket } from "ws";

const BASE = process.env.ARTHA_BASE_URL ?? "http://127.0.0.1:8080";
const PASSWORD = process.env.ARTHA_OWNER_PASSWORD ?? "stage-a-owner";
const TOPIC = process.argv[2] ?? "/topic/ticks.NSE.NIFTY 50";
const WANTED = Number(process.argv[3] ?? 10);

// 1) login -> session cookie
const login = await fetch(`${BASE}/api/v1/auth/login`, {
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: `password=${encodeURIComponent(PASSWORD)}`,
});
if (login.status !== 204) {
  console.error(`login failed: HTTP ${login.status} ${await login.text()}`);
  process.exit(1);
}
const sessionCookie = login.headers
  .getSetCookie()
  .map((c) => c.split(";")[0])
  .find((c) => c.startsWith("SESSION="));
if (!sessionCookie) {
  console.error("no SESSION cookie on login response");
  process.exit(1);
}
console.log(`login ok (${sessionCookie.slice(0, 16)}…)`);

// 2) STOMP over native WS with the session cookie
let received = 0;
const wsUrl = BASE.replace(/^http/, "ws") + "/ws";
const client = new Client({
  webSocketFactory: () => new WebSocket(wsUrl, { headers: { Cookie: sessionCookie } }),
  reconnectDelay: 0,
  onConnect: () => {
    console.log(`CONNECTED — subscribing ${TOPIC}`);
    client.subscribe(TOPIC, (message) => {
      received += 1;
      console.log(`#${received} ${message.body}`);
      if (received >= WANTED) {
        console.log(`✔ received ${received} frames end-to-end (mock feed → Redis → gateway WS)`);
        client.deactivate().then(() => process.exit(0));
      }
    });
  },
  onStompError: (frame) => {
    console.error(`STOMP ERROR: ${frame.headers.message} ${frame.body}`);
    process.exit(1);
  },
});
client.activate();

setTimeout(() => {
  console.error(`timeout: only ${received}/${WANTED} frames in 60s`);
  process.exit(1);
}, 60_000);
