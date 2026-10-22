import LogtoClient from '@logto/browser';
import { api, apiUrl, call, configureApi, type User } from './api/client';
import { discovery } from './discovery.svelte';
import { i18n } from './i18n/index.svelte';

const DEV_TOKEN_KEY = 'kinframe.devToken';
/** Name the server gives new accounts when the access token carries no name claim. */
export const PLACEHOLDER_NAME = 'New member';
const NEXT_KEY = 'kinframe.next';

export type AuthStatus = 'unknown' | 'signed_out' | 'signed_in';

function callbackUrl() {
	return `${location.origin}/callback`;
}

/** Only allow same-site relative paths as post-sign-in destinations. */
export function safeNext(next: string | null | undefined): string {
	if (!next || !next.startsWith('/') || next.startsWith('//')) return '/frames';
	return next;
}

class Auth {
	status = $state<AuthStatus>('unknown');
	mode = $state<'logto' | 'dev' | null>(null);
	me = $state<User | null>(null);
	/** Set when we were signed out because a token was rejected. */
	expired = $state(false);

	#logto: LogtoClient | null = null;
	#devToken: string | null = null;
	#tokenPromise: Promise<string | null> | null = null;

	constructor() {
		configureApi({
			token: () => this.getToken(),
			locale: () => i18n.locale,
			onUnauthorized: () => this.#onUnauthorized()
		});
	}

	get logto() {
		if (!this.#logto && discovery.logtoConfigured && discovery.doc) {
			const { issuer, web_client_id, api_resource } = discovery.doc.auth;
			this.#logto = new LogtoClient({
				endpoint: issuer.replace(/\/oidc\/?$/, ''),
				appId: web_client_id,
				resources: [api_resource],
				scopes: ['email', 'profile', 'offline_access']
			});
		}
		return this.#logto;
	}

	/** Decide the initial state from stored credentials. Call after discovery has loaded. */
	async init() {
		try {
			this.#devToken = localStorage.getItem(DEV_TOKEN_KEY);
		} catch {
			this.#devToken = null;
		}
		if (this.#devToken) {
			this.mode = 'dev';
		} else if (this.logto && (await this.logto.isAuthenticated().catch(() => false))) {
			this.mode = 'logto';
		}
		if (this.mode) {
			// Validate the session; a 401 flips us to signed_out via onUnauthorized.
			const ok = await this.loadMe();
			this.status = ok ? 'signed_in' : 'signed_out';
		} else {
			this.status = 'signed_out';
		}
	}

	async loadMe(): Promise<boolean> {
		try {
			this.me = await call(api.GET('/v1/me'));
			if (this.me?.locale && !localStorageHas('kinframe.locale')) {
				const l = this.me.locale.toLowerCase();
				if (l.startsWith('nl')) i18n.set('nl');
			}
			return true;
		} catch {
			return false;
		}
	}

	async getToken(): Promise<string | null> {
		if (this.mode === 'dev') return this.#devToken;
		if (this.mode === 'logto' && this.logto && discovery.doc) {
			// Coalesce concurrent refreshes.
			this.#tokenPromise ??= this.logto
				.getAccessToken(discovery.doc.auth.api_resource)
				.catch(() => null)
				.finally(() => (this.#tokenPromise = null));
			return this.#tokenPromise;
		}
		return null;
	}

	rememberNext(next: string | null | undefined) {
		try {
			sessionStorage.setItem(NEXT_KEY, safeNext(next));
		} catch {
			/* ignore */
		}
	}

	takeNext(): string {
		try {
			const n = sessionStorage.getItem(NEXT_KEY);
			sessionStorage.removeItem(NEXT_KEY);
			return safeNext(n);
		} catch {
			return '/frames';
		}
	}

	/** Start the Logto PKCE redirect flow. */
	async signIn(next?: string) {
		if (!this.logto) throw new Error('Sign-in is not configured on this server.');
		this.rememberNext(next);
		await this.logto.signIn({ redirectUri: callbackUrl() });
	}

	/** Finish the redirect flow on /callback. Returns where to go next. */
	async handleCallback(url: string): Promise<string> {
		if (!this.logto) throw new Error('Sign-in is not configured on this server.');
		await this.logto.handleSignInCallback(url);
		this.mode = 'logto';
		this.expired = false;
		if (!(await this.loadMe())) throw new Error('Could not load your profile.');
		this.status = 'signed_in';
		const next = this.takeNext();
		return (await this.#ensureDisplayName()) ? next : `/settings?welcome=1&next=${encodeURIComponent(next)}`;
	}

	/**
	 * New accounts start with a placeholder name. Take the real one from the ID token;
	 * returns false when there is none, so the caller can ask the person once.
	 */
	async #ensureDisplayName(): Promise<boolean> {
		if (!this.logto || this.me?.display_name !== PLACEHOLDER_NAME) return true;
		try {
			const claims = await this.logto.getIdTokenClaims();
			const name = String(claims.name ?? claims.username ?? '').trim().slice(0, 40);
			if (!name) return false;
			this.me = await call(api.PATCH('/v1/me', { body: { display_name: name } }));
			return true;
		} catch {
			return false;
		}
	}

	/** Development servers only: POST /dev/token {email, name}. */
	async devSignIn(email: string, name: string): Promise<void> {
		const res = await fetch(apiUrl('/dev/token'), {
			method: 'POST',
			headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
			body: JSON.stringify({ email, name })
		});
		if (!res.ok) throw new Error(`Dev sign-in failed (HTTP ${res.status})`);
		const body = (await res.json()) as { access_token?: string; token?: string };
		const token = body.access_token ?? body.token;
		if (!token) throw new Error('Dev sign-in returned no token');
		this.#devToken = token;
		try {
			localStorage.setItem(DEV_TOKEN_KEY, token);
		} catch {
			/* session-only */
		}
		this.mode = 'dev';
		this.expired = false;
		if (!(await this.loadMe())) {
			this.#clearDev();
			throw new Error('The server rejected the dev token');
		}
		this.status = 'signed_in';
	}

	async signOut() {
		const wasLogto = this.mode === 'logto';
		this.#clearDev();
		this.mode = null;
		this.me = null;
		this.status = 'signed_out';
		if (wasLogto && this.logto) await this.logto.signOut(location.origin);
	}

	#clearDev() {
		this.#devToken = null;
		try {
			localStorage.removeItem(DEV_TOKEN_KEY);
		} catch {
			/* ignore */
		}
	}

	#onUnauthorized() {
		if (this.status !== 'signed_in' && this.status !== 'unknown') return;
		if (this.mode === 'dev') this.#clearDev();
		this.mode = this.mode === 'logto' ? 'logto' : null;
		this.me = null;
		this.expired = this.status === 'signed_in';
		this.status = 'signed_out';
	}
}

function localStorageHas(key: string) {
	try {
		return localStorage.getItem(key) !== null;
	} catch {
		return false;
	}
}

export const auth = new Auth();
