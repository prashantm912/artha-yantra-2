import { useSyncExternalStore } from 'react';
import { wsClient, type WsState } from '../lib/wsClient.ts';

/** Subscribes the TopBar pill to the WS singleton's connection state. */
export function useWsState(): WsState {
  return useSyncExternalStore(
    (cb) => wsClient.onState(cb),
    () => wsClient.state,
    () => 'idle',
  );
}
