import { registerPlugin } from '@capacitor/core';

import type { BackgroundPlugin } from './definitions';

const Background = registerPlugin<BackgroundPlugin>('Background', {
  web: () => import('./web').then((m) => new m.BackgroundWeb()),
});

export * from './definitions';
export { Background };
