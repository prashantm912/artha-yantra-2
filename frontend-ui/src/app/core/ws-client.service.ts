import { Injectable, OnDestroy, signal } from '@angular/core';
import { Client, type StompSubscription } from '@stomp/stompjs';
import { Observable, Subject, share } from 'rxjs';

/** Connection states the TopBar pill renders. */
export type WsState = 'idle' | 'connecting' | 'connected' | 'reconnecting';

/**
 * Exponential backoff with full jitter: 1s -> 30s cap (C-2.25). Pure so the fake-timer spec can
 * pin the envelope exactly.
 */
export function nextReconnectDelay(previousMs: number, random: () => number = Math.random): number {
  const doubled = Math.min(previousMs <= 0 ? 1000 : previousMs * 2, 30_000);
  const jittered = doubled * (0.5 + random() / 2); // [0.5x, 1x] of the target
  return Math.round(jittered);
}

/**
 * THE one STOMP client (C-2.25 / D9): @stomp/stompjs over NATIVE WebSocket to the gateway /ws
 * (CD-3 subset broker). Heartbeats 10s/10s; exponential reconnect with jitter; REFCOUNTED
 * subscribe/unsubscribe per destination (the datafeed pattern generalized); on every reconnect
 * {@link reconnects$} fires so each store re-fetches its REST snapshot (at-least-once display).
 * Components NEVER touch STOMP - stores bridge these streams into signals.
 */
@Injectable({ providedIn: 'root' })
export class WsClientService implements OnDestroy {
  readonly state = signal<WsState>('idle');

  /** Emits after every successful connect EXCEPT the first (gap-healing trigger). */
  readonly reconnects$ = new Subject<void>();

  private readonly client: Client;
  private readonly topics = new Map<string, TopicEntry>();
  private everConnected = false;
  private currentDelay = 1000;

  constructor() {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    this.client = new Client({
      brokerURL: `${protocol}://${location.host}/ws`,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: this.currentDelay,
      onConnect: () => {
        this.state.set('connected');
        this.currentDelay = 1000;
        this.client.reconnectDelay = this.currentDelay;
        for (const [destination, entry] of this.topics) {
          this.brokerSubscribe(destination, entry);
        }
        if (this.everConnected) {
          this.reconnects$.next();
        }
        this.everConnected = true;
      },
      onWebSocketClose: () => {
        if (this.state() !== 'idle') {
          this.state.set('reconnecting');
        }
        for (const entry of this.topics.values()) {
          entry.subscription = undefined; // broker subs died with the socket
        }
        this.currentDelay = nextReconnectDelay(this.currentDelay);
        this.client.reconnectDelay = this.currentDelay;
      },
    });
  }

  /** Connects once (idempotent); stores call this from their constructors. */
  activate(): void {
    if (this.state() === 'idle') {
      this.state.set('connecting');
      this.client.activate();
    }
  }

  /** Raw frame bodies for a destination; refcounted - the broker sub dies with the last reader. */
  topic(destination: string): Observable<string> {
    return new Observable<string>((observer) => {
      this.activate();
      let entry = this.topics.get(destination);
      if (!entry) {
        entry = { count: 0, sink: new Subject<string>() };
        this.topics.set(destination, entry);
      }
      entry.count++;
      if (this.client.connected && !entry.subscription) {
        this.brokerSubscribe(destination, entry);
      }
      const inner = entry.sink.subscribe(observer);
      return () => {
        inner.unsubscribe();
        const current = this.topics.get(destination);
        if (current && --current.count <= 0) {
          current.subscription?.unsubscribe();
          this.topics.delete(destination);
        }
      };
    }).pipe(share());
  }

  private brokerSubscribe(destination: string, entry: TopicEntry): void {
    entry.subscription = this.client.subscribe(destination, (frame) =>
      entry.sink.next(frame.body),
    );
  }

  ngOnDestroy(): void {
    void this.client.deactivate();
    this.state.set('idle');
  }
}

interface TopicEntry {
  count: number;
  sink: Subject<string>;
  subscription?: StompSubscription;
}
