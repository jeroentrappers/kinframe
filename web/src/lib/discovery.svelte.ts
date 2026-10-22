import { setApiBase, type Discovery, type Limits } from './api/client';
import { appUrl, isStorefrontHost } from './storefront';

export const DEFAULT_LIMITS: Required<Limits> = {
	max_items_per_send: 200,
	max_video_seconds: 300,
	max_upload_bytes: 2 * 1024 ** 3,
	trash_retention_days: 30
};

/**
 * Discovery may carry fields the contract doesn't list yet. `dev_auth` (boolean) is how a
 * server in development mode advertises `POST /dev/token`.
 */
type DiscoveryDoc = Discovery & { dev_auth?: boolean };

class DiscoveryState {
	doc = $state<DiscoveryDoc | null>(null);
	error = $state<string | null>(null);
	loading = $state(false);

	get name() {
		return this.doc?.name ?? 'kinframe';
	}

	get limits(): Required<Limits> {
		return { ...DEFAULT_LIMITS, ...(this.doc?.limits ?? {}) };
	}

	/** The operator's pre-order shop, or null (the default: open-source servers have no shop). */
	get shop() {
		return this.doc?.shop ?? null;
	}

	/** Consumer brand for shop texts; the server name when the shop doesn't set one. */
	get brand() {
		return this.doc?.shop?.brand || this.name;
	}

	/**
	 * True on one of the shop's storefront domains (`shop.domains`): the app is then the brand's shop only, and
	 * everything else lives on the main origin (`appUrl`).
	 */
	get storefront() {
		return typeof location !== 'undefined' && isStorefrontHost(location.host, this.doc?.shop?.domains);
	}

	/** Name for page titles and the wordmark: the brand on a storefront, else the software. */
	get siteName() {
		return this.storefront ? this.brand : 'kinframe';
	}

	/** `path` on the app's main origin (discovery `web`), for links from a storefront; null when unknown. */
	appUrl(path = '/') {
		return appUrl(this.doc?.web, this.doc?.api, path, typeof location !== 'undefined' ? location.origin : undefined);
	}

	/** Logto is usable when the server published an issuer and a web client id. */
	get logtoConfigured() {
		return Boolean(this.doc?.auth?.issuer && this.doc?.auth?.web_client_id);
	}

	/**
	 * Dev sign-in (POST /dev/token) is offered when the build asks for it, the server says so
	 * (`dev_auth`, not yet in the contract), or the server publishes no OIDC issuer: the Go
	 * server refuses to start without an issuer unless KINFRAME_DEV_AUTH=1.
	 */
	get devAuth() {
		if (import.meta.env.VITE_DEV_AUTH === '1' || this.doc?.dev_auth === true) return true;
		return this.doc !== null && !this.doc.auth?.issuer;
	}

	async load(): Promise<DiscoveryDoc | null> {
		this.loading = true;
		this.error = null;
		try {
			const res = await fetch('/.well-known/kinframe', { headers: { Accept: 'application/json' } });
			if (!res.ok) throw new Error(`HTTP ${res.status}`);
			const doc = (await res.json()) as DiscoveryDoc;
			this.doc = doc;
			// A storefront calls the API on its own origin (the same server behind another vhost): no CORS, and
			// the server knows which storefront a reservation came from.
			setApiBase(this.storefront ? `${location.origin}/v1` : doc.api);
			return doc;
		} catch (e) {
			this.error = e instanceof Error ? e.message : String(e);
			return null;
		} finally {
			this.loading = false;
		}
	}
}

export const discovery = new DiscoveryState();
