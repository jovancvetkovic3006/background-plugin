export interface BackgroundPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
