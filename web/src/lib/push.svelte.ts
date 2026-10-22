import { dev } from '$app/environment';
import { api, call } from './api/client';
import { discovery } from './discovery.svelte';
import { i18n } from './i18n/index.svelte';
import { base64UrlToBytes, sameKey } from './pushPayload';

const ENDPOINT_KEY = 'kinframe.push.endpointId';
const OFFER_KEY = 'kinframe.push.offerDismissed';

const store = {
	get(k: string) {
		try {
			return localStorage.getItem(k);
		} catch {
			return null;
		}
	},
	set(k: string, v: string | null) {
		try {
			if (v === null) localStorage.removeItem(k);
			else localStorage.setItem(k, v);
		} catch {
			/* storage unavailable */
		}
	}
};

/** "Chrome on Android" style label so people can tell their devices apart. */
function deviceName(): string {
	const ua = navigator.userAgent;
	const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
	const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Mac OS X/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : '';
	return (os ? `${browser} on ${os}` : browser).slice(0, 60);
}

/**
 * Browser push (WebPush) for this device: the service worker, the subscription and its
 * registration on the server (POST /v1/me/push-endpoints).
 */
class PushState {
	supported = $state(false);
	permission = $state<NotificationPermission>('default');
	subscribed = $state(false);
	busy = $state(false);
	/** Set by the send page so the next page can offer notifications at the right moment. */
	justSent = $state(false);
	#offerDismissed = $state(false);
	#ready = false;

	/** Push works in this browser and the server has a VAPID key. */
	get available() {
		return this.supported && Boolean(discovery.doc?.webpush_public_key);
	}

	/** Offer notifications after a send: only once, and only if the browser hasn't decided yet. */
	get shouldOffer() {
		return this.available && this.permission === 'default' && !this.subscribed && !this.#offerDismissed;
	}

	dismissOffer() {
		this.#offerDismissed = true;
		store.set(OFFER_KEY, '1');
	}

	/** Read the current state; re-registers an existing subscription (the server may have dropped it). */
	async init() {
		if (this.#ready || typeof window === 'undefined') return;
		this.#ready = true;
		this.supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
		this.#offerDismissed = store.get(OFFER_KEY) === '1';
		if (!this.supported) return;
		this.permission = Notification.permission;
		navigator.serviceWorker.addEventListener('message', (e: MessageEvent) => {
			const d = e.data as { type?: string; url?: string } | null;
			if (d?.type === 'kinframe.navigate' && d.url?.startsWith('/')) {
				void import('$app/navigation').then(({ goto }) => goto(d.url!));
			}
		});
		try {
			const reg = await navigator.serviceWorker.getRegistration('/');
			const sub = await reg?.pushManager.getSubscription();
			this.subscribed = Boolean(sub) && this.permission === 'granted';
			if (sub && this.subscribed) await this.#register(sub);
		} catch {
			this.subscribed = false;
		}
	}

	async #registration(): Promise<ServiceWorkerRegistration> {
		const reg = await navigator.serviceWorker.register('/service-worker.js', { type: dev ? 'module' : 'classic', scope: '/' });
		await navigator.serviceWorker.ready;
		return reg;
	}

	async #register(sub: PushSubscription) {
		const json = sub.toJSON();
		const ep = await call(
			api.POST('/v1/me/push-endpoints', {
				body: {
					kind: 'webpush',
					endpoint: json.endpoint ?? sub.endpoint,
					webpush_keys: { p256dh: json.keys?.p256dh, auth: json.keys?.auth },
					device_name: deviceName(),
					locale: i18n.tag
				}
			})
		);
		store.set(ENDPOINT_KEY, ep.id);
	}

	/** Ask for permission (must run from a click), subscribe and register. Resolves to success. */
	async enable(): Promise<boolean> {
		if (!this.available || this.busy) return false;
		this.busy = true;
		try {
			this.permission = await Notification.requestPermission();
			if (this.permission !== 'granted') return false;
			const key = base64UrlToBytes(discovery.doc!.webpush_public_key!);
			const reg = await this.#registration();
			let sub = await reg.pushManager.getSubscription();
			if (sub && !sameKey(sub.options.applicationServerKey, key)) {
				await sub.unsubscribe();
				sub = null;
			}
			sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
			await this.#register(sub);
			this.subscribed = true;
			return true;
		} finally {
			this.busy = false;
		}
	}

	/** Unsubscribe this browser and remove its endpoint from the server. */
	async disable() {
		if (this.busy) return;
		this.busy = true;
		try {
			const id = store.get(ENDPOINT_KEY);
			if (id) {
				await call(api.DELETE('/v1/me/push-endpoints/{endpointId}', { params: { path: { endpointId: id } } })).catch(() => {});
				store.set(ENDPOINT_KEY, null);
			}
			const reg = await navigator.serviceWorker.getRegistration('/');
			await (await reg?.pushManager.getSubscription())?.unsubscribe();
			this.subscribed = false;
		} finally {
			this.busy = false;
		}
	}

	/** Push texts are localized by the server; tell it when the language changes. */
	async syncLocale() {
		if (!this.subscribed) return;
		const reg = await navigator.serviceWorker.getRegistration('/');
		const sub = await reg?.pushManager.getSubscription();
		if (sub) await this.#register(sub).catch(() => {});
	}

	/** Forget local state on sign-out (the server endpoint belongs to the old account). */
	async signOut() {
		if (this.subscribed) await this.disable().catch(() => {});
	}
}

export const push = new PushState();
