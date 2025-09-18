import { registerPlugin } from '@capacitor/core';

import type { audioplayerPlugin } from './definitions';

const audioplayer = registerPlugin<audioplayerPlugin>('audioplayer', {
  web: () => import('./web').then((m) => new m.audioplayerWeb()),
});

export * from './definitions';
export { audioplayer };
