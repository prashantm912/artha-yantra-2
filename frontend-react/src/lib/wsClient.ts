import { Client, type StompSubscription } from '@stomp/stompjs';

// THE one STOMP client (D9), ported framework-free from frontend-ui/core/ws-client.service.ts.
// @stomp/stompjs over native WebSocket to the gateway /ws; heartbeats 10s/10s; exponential reconnect
// with jitter; REFCOUNTED subscribe/unsubscribe per destination; on every reconnect (except the
// first) the reconnect listeners fire so each query re-fetches its REST snapshot (gap-heal).
// Components never touch STOMP — the ws.store + the tick bridge wrap this (master plan §20).

export type WsState = 'idle' | 'connecting' | 'connected' | 'reconnecting';

/** Exponential backoff with full jitter: 1s → 30s cap (C-2.25). Pure (verbatim port). */
export function nextReconnectDelay(previousMs: number, random: () => number = Math.random): number {
  const doubled = Math.min(previousMs <= 0 ? 1000 : previousMs * 2, 30_000);
  const jittered = doubled * (0.5 + random() / 2); // [0.5x, 1x] of the target
  return Math.round(jittered);
}

interface TopicEntry {
  count: number;
  listeners: Set<(body: string) => void>;
  subscription?: StompSubscription;
}

class WsClient {
  private client: Client | null = null;
  private _state: WsState = 'idle';
  private readonly topics = new Map<string, TopicEntry>();
  private everConnected = false;
  private currentDelay = 1000;
  private readonly stateListeners = new Set<(s: WsState) => void>();
  private readonly reconnectListeners = new Set<() => void>();

  get state(): WsState {
    return this._state;
  }

  private setState(s: WsState): void {
    this._state = s;
    this.stateListeners.forEach((l) => l(s));
  }

  /** Subscribe to connection-state changes (TopBar pill). Returns an unsubscribe fn. */
  onState(listener: (s: WsState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  /** Fires after every reconnect except the first (gap-heal → invalidate queries). */
  onReconnect(listener: () => void): () => void {
    this.reconnectListeners.add(listener);
    return () => this.reconnectListeners.delete(listener);
  }

  /** Connects once (idempotent). Lazily builds the STOMP client so module import has no side effect. */
  activate(): void {
    if (this._state !== 'idle') return;
    this.setState('connecting');
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    this.client = new Client({
      brokerURL: `${protocol}://${location.host}/ws`,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: this.currentDelay,
      onConnect: () => {
        this.setState('connected');
        this.currentDelay = 1000;
        if (this.client) this.client.reconnectDelay = this.currentDelay;
        for (const [destination, entry] of this.topics) {
          this.brokerSubscribe(destination, entry);
        }
        if (this.everConnected) this.reconnectListeners.forEach((l) => l());
        this.everConnected = true;
      },
      onWebSocketClose: () => {
        if (this._state !== 'idle') this.setState('reconnecting');
        for (const entry of this.topics.values()) {
          entry.subscription = undefined; // broker subs died with the socket
        }
        this.currentDelay = nextReconnectDelay(this.currentDelay);
        if (this.client) this.client.reconnectDelay = this.currentDelay;
      },
    });
    this.client.activate();
  }

  /** Refcounted subscribe; the broker sub dies with the last reader. Returns an unsubscribe fn. */
  topic(destination: string, onMessage: (body: string) => void): () => void {
    this.activate();
    let entry = this.topics.get(destination);
    if (!entry) {
      entry = { count: 0, listeners: new Set() };
      this.topics.set(destination, entry);
    }
    entry.count++;
    entry.listeners.add(onMessage);
    if (this.client?.connected && !entry.subscription) {
      this.brokerSubscribe(destination, entry);
    }
    return () => {
      const current = this.topics.get(destination);
      if (!current) return;
      current.listeners.delete(onMessage);
      if (--current.count <= 0) {
        current.subscription?.unsubscribe();
        this.topics.delete(destination);
      }
    };
  }

  private brokerSubscribe(destination: string, entry: TopicEntry): void {
    if (!this.client) return;
    entry.subscription = this.client.subscribe(destination, (frame) =>
      entry.listeners.forEach((l) => l(frame.body)),
    );
  }

  deactivate(): void {
    void this.client?.deactivate();
    this.setState('idle');
  }
}

/** App-wide singleton. */
export const wsClient = new WsClient();
