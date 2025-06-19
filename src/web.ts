import { WebPlugin } from '@capacitor/core';

import type { BackgroundPlugin } from './definitions';

export class BackgroundWeb extends WebPlugin implements BackgroundPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
