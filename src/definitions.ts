export interface audioplayerPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
