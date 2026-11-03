// The browser frame's device credential (README §3.2), kept in localStorage. It is
// scoped to the API origin, so one browser profile can be a frame of one server
// at a time, and it never leaves the browser except to POST /v1/devices/token.

export interface DeviceCredentials {
	device_id: string;
	device_secret: string;
	enrolled_at: string;
}

type KeyValueStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export const CREDENTIALS_KEY = 'kinframe.browser-frame.v1';

/** The storage key for an API origin ('' = same origin as the page). */
export function credentialsKey(apiBase: string): string {
	return apiBase ? `${CREDENTIALS_KEY}@${apiBase}` : CREDENTIALS_KEY;
}

export class CredentialStore {
	readonly #storage: KeyValueStore | null;
	readonly #key: string;

	constructor(storage: KeyValueStore | null, key = CREDENTIALS_KEY) {
		this.#storage = storage;
		this.#key = key;
	}

	load(): DeviceCredentials | null {
		try {
			const raw = this.#storage?.getItem(this.#key);
			if (!raw) return null;
			const c = JSON.parse(raw) as Partial<DeviceCredentials>;
			return typeof c.device_id === 'string' && typeof c.device_secret === 'string'
				? { device_id: c.device_id, device_secret: c.device_secret, enrolled_at: c.enrolled_at ?? '' }
				: null;
		} catch {
			return null;
		}
	}

	save(c: DeviceCredentials): void {
		try {
			this.#storage?.setItem(this.#key, JSON.stringify(c));
		} catch {
			/* private mode or full: the frame works until the page reloads */
		}
	}

	clear(): void {
		try {
			this.#storage?.removeItem(this.#key);
		} catch {
			/* nothing stored */
		}
	}
}

/** localStorage when the browser allows it. */
export function browserStorage(): KeyValueStore | null {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage;
	} catch {
		return null;
	}
}
