// The notification buttons' labels in the app's language, for the service worker (which has no catalogs).
// The app writes them to a cache entry whenever the language changes; the worker reads them when a push arrives.

export const PUSH_LABELS_CACHE = 'kinframe-push-labels';
export const LABELS_URL = '/__kinframe/push-labels.json';

export async function storePushLabels(labels: Record<string, string>): Promise<void> {
	if (typeof caches === 'undefined') return;
	try {
		const cache = await caches.open(PUSH_LABELS_CACHE);
		await cache.put(LABELS_URL, new Response(JSON.stringify(labels), { headers: { 'Content-Type': 'application/json' } }));
	} catch {
		/* storage unavailable: the worker falls back to English */
	}
}
