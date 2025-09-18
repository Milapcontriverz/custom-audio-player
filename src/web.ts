import { WebPlugin } from '@capacitor/core';

import type { audioplayerPlugin } from './definitions';

export class audioplayerWeb extends WebPlugin implements audioplayerPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
