import { setApiBase, type Discovery, type Limits, type PhotoSourceProvider } from './api/client';
import { offeredProviders } from './photoSources';
import { appUrl, isStorefrontHost } from './storefront';

export const DEFAULT_LIMITS: Required<Limits> = {
	max_items_per_send: 200,
	max_video_seconds: 300,
	max_upload_bytes: 2 * 1024 ** 3,
	trash_retention_days: 30,
	max_feeds_per_user: 20,
	max_subscriptions_per_feed: 100,
	max_feed_backfill_items: 5000,
	max_birthdays_per_feed: 200,
	max_photo_sources_per_target: 5,
	max_photo_sources_per_user: 20,
	max_photo_source_items: 10000,
	max_photo_source_run_bytes: 2147483648,
	max_google_import_items: 500,
	max_email_addresses_per_target: 5,
	max_email_senders_per_address: 200,
	max_email_message_bytes: 52428800,
	max_email_messages_per_address_day: 200,
	max_email_held_messages: 100,
	max_telegram_chats_per_target: 5,
	max_telegram_chats_per_user: 20,
	max_telegram_items_per_chat_hour: 300,
	max_comment_chars: 2000,
	max_message_chars: 4000,
	max_mentions: 20,
	max_message_items: 30,
	max_group_members: 100,
	max_circles_per_feed: 20,
	max_voice_seconds: 300,
	message_edit_hours: 24
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

	/** Feeds and subscriptions (docs/spec/feeds.md): the server says so in discovery. Off hides every feed UI. */
	get feeds() {
		return this.doc?.feeds === true;
	}

	/** Birthday lists in feeds (docs/spec/birthdays.md): only together with feeds. Off hides every birthday UI. */
	get birthdays() {
		return this.feeds && this.doc?.birthdays === true;
	}

	/** Immich and iCloud sources for albums and feeds (docs/spec/photo-sources.md): off hides every source UI. */
	get photoSources() {
		return this.doc?.photo_sources === true && this.photoSourceProviders.length > 0;
	}

	/** The providers this server allows (`icloud_link` can be off on its own). */
	get photoSourceProviders(): PhotoSourceProvider[] {
		if (this.doc?.photo_sources !== true) return [];
		return offeredProviders(this.doc.photo_source_providers);
	}

	/** Whether sources may reach home-network addresses; false shows the "use a public address" hint. */
	get photoSourcesPrivateNetworks() {
		return this.doc?.photo_sources_private_networks === true;
	}

	/** "Add from Google Photos": the operator configured a Google OAuth client. */
	get googlePhotosImport() {
		return this.doc?.google_photos_import === true;
	}

	/** Email to frame (docs/spec/email-inbox.md): off hides every "Email photos to…" section. */
	get emailInbox() {
		return this.doc?.email_inbox === true;
	}

	/** The inbox addresses' mail domain ("in.kinframe.appmire.be"), for the create preview. */
	get emailInboxDomain() {
		return this.emailInbox ? (this.doc?.email_inbox_domain ?? null) : null;
	}

	/** The Telegram bridge (docs/spec/telegram.md): needs the bot's username for t.me links; off hides every Telegram UI. */
	get telegram() {
		return this.doc?.telegram === true && !!this.doc.telegram_bot_username;
	}

	get telegramBot(): string | null {
		return this.telegram ? (this.doc?.telegram_bot_username ?? null) : null;
	}

	/** The bot runs in privacy mode: a group must make it an admin before it sees photos. */
	get telegramPrivacyMode() {
		return this.telegram && this.doc?.telegram_privacy_mode === true;
	}

	// Family social (docs/spec/family-social.md, README §5.18–§5.20). Each area is hidden until the server says so.
	/** Home timeline, threads, people's reactions, mentions, the `member` role and circles: only with feeds. */
	get social() {
		return this.feeds && this.doc?.social === true;
	}

	/** Conversations (direct, group, family, circle), blocks and reports. */
	get chats() {
		return this.doc?.chats === true;
	}

	/** Voice messages in chats (the server has ffmpeg). */
	get voiceMessages() {
		return this.chats && this.doc?.voice_messages === true;
	}

	/** Link previews in chat messages (the operator's opt-in). */
	get linkPreviews() {
		return this.chats && this.doc?.link_previews === true;
	}

	/** Freshness, photo requests, reminders, streaks, prompts, On this day, summaries, recaps and milestones. */
	get engagement() {
		return this.doc?.engagement === true;
	}

	/** The daily or weekly email digest (the server can send mail). */
	get emailDigest() {
		return this.doc?.email_digest === true;
	}

	// Leaving a family and families on other servers (README §5.21, §5.22). Hidden until the server says so.
	/** The leave choices, taking photos away, keep requests, the account deletion handover and the memories filter. */
	get leaving() {
		return this.doc?.leaving === true;
	}

	/** Frames here following families on other servers, and families here allowing frames on other servers. */
	get remoteFamilies() {
		return this.doc?.remote_families === true;
	}

	/** Remote help: the operator can open a temporary tailnet tunnel to a frame (docs/spec/remote-help.md). */
	get remoteHelp() {
		return this.doc?.remote_help === true;
	}

	/** The server's stable id (set by servers that know about other servers; the web stays single-server). */
	get serverId() {
		return this.doc?.server_id ?? null;
	}

	/** The server's label (`server_name`), else its host. */
	get serverLabel() {
		return this.doc?.server_name || (typeof location !== 'undefined' ? location.host : this.name);
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
