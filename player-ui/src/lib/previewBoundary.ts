export function acceptedPreviewMessage(event: MessageEvent, ownOrigin: string, parent: Window, self: Window, enabled: boolean): boolean {
  return enabled && parent !== self && event.source === parent && event.origin === ownOrigin
    && event.data?.type === 'dfs-admin-preview' && event.data.branding != null
    && typeof event.data.branding === 'object' && !Array.isArray(event.data.branding);
}
