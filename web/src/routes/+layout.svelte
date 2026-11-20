<script lang="ts">
	import '@fontsource-variable/fraunces';
	import '../app.css';
	import { onMount, untrack, type Snippet } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { frames } from '$lib/frames.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { live } from '$lib/live.svelte';
	import { push } from '$lib/push.svelte';
	import { badge, chats } from '$lib/social.svelte';
	import { personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { applyDocumentLocale, hasSavedLocale, i18n, t, tn } from '$lib/i18n/index.svelte';
	import { watching } from '$lib/googleImport';
	import { isStorefrontPath, storefrontLocale } from '$lib/storefront';
	import AppShell from '$components/AppShell.svelte';
	import Toaster from '$components/Toaster.svelte';
	import Logo from '$components/Logo.svelte';
	import Icon from '$components/Icon.svelte';
	import TvMiniBar from '$components/TvMiniBar.svelte';
	import { requestSendUrl } from '$lib/engagement';
	import { startCrashReporting } from '$lib/crash';

	let { children }: { children: Snippet } = $props();

	/** Routes that render without a session. Everything else (including /f/{code} and /i/{token})
	 *  bounces to the landing page with ?next= and comes back after sign-in. */
	const PUBLIC = [/^\/$/, /^\/callback$/, /^\/google-import\/return$/, /^\/tv\//, /^\/shop(\/|$)/, /^\/(privacy|terms)$/, /^\/convert$/, /^\/station\/[^/]+$/, /^\/stations$/];
	const isPublic = $derived(PUBLIC.some((r) => r.test(page.url.pathname)));
	/** The TV page (/tv/{frameId}?t=…) is full screen and needs no session: the link's token is the credential. */
	const isTv = $derived(page.url.pathname.startsWith('/tv/'));
	/** The shop pages (/shop, /shop/cancel) have their own header, signed in or not. */
	const isShop = $derived(/^\/shop(\/|$)/.test(page.url.pathname));
	/** The privacy policy and terms of use, likewise readable signed in or not, with their own header. */
	const isLegal = $derived(/^\/(privacy|terms)$/.test(page.url.pathname));
	/** So does the convert tool (/convert): it's for people who don't have a frame on kinframe yet. */
	const isConvert = $derived(page.url.pathname === '/convert');
	/** The public weather stations (/stations) and each one's page (/station/{id}): signed in or not, their own header. */
	const isStation = $derived(/^\/(station\/[^/]+|stations)$/.test(page.url.pathname));

	/** Google's consent comes back to /google-import/return, usually in a popup: no shell, no waiting for sign-in. */
	const isGoogleReturn = $derived(page.url.pathname === '/google-import/return');

	let booting = $state(true);

	/** On a storefront, app pages live on the main origin: same path there (sign-in only happens there). */
	function leaveStorefront(): boolean {
		if (isStorefrontPath(location.pathname)) return false;
		const target = discovery.appUrl(location.pathname + location.search + location.hash);
		if (!target) return false;
		location.replace(target);
		return true;
	}

	async function boot() {
		booting = true;
		const [doc] = await Promise.all([discovery.load(), i18n.ready]);
		void startCrashReporting(doc?.sentry_dsn);
		if (doc && discovery.storefront) {
			if (leaveStorefront()) return; // keep the splash while the browser leaves
			if (!hasSavedLocale()) {
				const langs = navigator.languages?.length ? navigator.languages : [navigator.language];
				await i18n.use(storefrontLocale(langs, doc.shop?.default_locale));
			}
		} else if (doc) {
			await auth.init();
		}
		booting = false;
	}

	// Client-side navigation on a storefront to an app page (an old bookmark in history, a typed link).
	$effect(() => {
		if (!booting && discovery.storefront && !isStorefrontPath(page.url.pathname)) leaveStorefront();
	});

	/** The morning notice on an open socket carries ids only: look the names up (§8.1). */
	async function showBirthdaysToday(list: { feed_id: string; birthday_id: string }[]) {
		const people = await Promise.all(
			list.map((b) =>
				call(api.GET('/v1/feeds/{feedId}/birthdays/{birthdayId}', { params: { path: { feedId: b.feed_id, birthdayId: b.birthday_id } } })).catch(() => null)
			)
		);
		const names = [...new Set(people.filter((p) => p !== null).map((p) => p.display_name))];
		if (!names.length) return;
		const feedId = list[0]!.feed_id;
		toasts.show(t('live.birthdayToday', { names: new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(names) }), {
			action: { label: t('common.review'), run: () => goto(`/feeds/${feedId}?tab=birthdays`) },
			duration: 12000
		});
	}

	onMount(() => {
		applyDocumentLocale(i18n.locale);
		void boot();
		const off = live.on((e) => {
			chats.handle(e);
			if (e.type === 'frame.status') frames.setStatus(e.frame_id, e.online, e.last_seen_at);
			else if (e.type === 'membership.updated') void frames.load(true);
			else if (e.type === 'join_request') {
				const name = frames.get(e.frame_id)?.name ?? '';
				toasts.show(t('live.joinRequest', { name: e.request.user?.display_name ?? '?', frame: name }), {
					action: { label: t('common.review'), run: () => goto(`/frames/${e.frame_id}?tab=people`) },
					duration: 9000
				});
			}
			// Feeds (docs/spec/feeds.md §8). Pages that show the feed or frame refresh themselves.
			else if (e.type === 'feed.membership_updated') void feeds.load(true);
			else if (e.type === 'feed.join_request') {
				const feed = feeds.get(e.feed_id)?.name ?? '';
				toasts.show(t('live.feedJoinRequest', { name: e.request.user?.display_name ?? '?', feed }), {
					action: { label: t('common.review'), run: () => goto(`/feeds/${e.feed_id}?tab=members`) },
					duration: 9000
				});
			} else if (e.type === 'subscription.request') {
				const s = e.subscription;
				if (s.status !== 'pending') return;
				toasts.show(t('live.subscriptionRequest', { name: s.requested_by_name ?? '?', feed: s.feed.name, frame: s.frame.name }), {
					action: { label: t('common.review'), run: () => goto(`/feeds/${e.feed_id}?tab=subscriptions`) },
					duration: 9000
				});
			} else if (e.type === 'subscription.updated') {
				// For the frame side; the feed admin who decided already saw it happen.
				const s = e.subscription;
				if (s.can_decide) return;
				if (s.status === 'active' && s.activated_at && Date.now() - Date.parse(s.activated_at) < 60_000)
					toasts.success(t('live.subscriptionApproved', { feed: s.feed.name, frame: s.frame.name }));
				else if (s.status === 'rejected') toasts.show(t('live.subscriptionRejected', { feed: s.feed.name, frame: s.frame.name }));
				else if (s.status === 'removed' && s.removed_reason === 'removed_by_feed')
					toasts.show(t('live.subscriptionRemoved', { feed: s.feed.name, frame: s.frame.name }));
				else if (s.status === 'removed' && s.removed_reason === 'feed_deleted')
					toasts.show(t('live.feedDeleted', { feed: s.feed.name, frame: s.frame.name }));
			}
			// Birthdays (docs/spec/birthdays.md §8). The feed's Birthdays tab updates itself.
			else if (e.type === 'birthday.suggested') {
				const b = e.birthday;
				if (b.status !== 'pending' || (b.created_by_id != null && b.created_by_id === auth.me?.id)) return;
				toasts.show(t('live.birthdaySuggested', { name: b.created_by_name ?? '?', person: b.display_name }), {
					action: { label: t('common.review'), run: () => goto(`/feeds/${e.feed_id}?tab=birthdays`) },
					duration: 9000
				});
			} else if (e.type === 'birthday.decided') {
				const b = e.birthday;
				if (b.status === 'approved') toasts.success(t('live.birthdayApproved', { person: b.display_name }));
				else if (b.status === 'rejected') toasts.show(t('live.birthdayRejected', { person: b.display_name }));
			} else if (e.type === 'birthday.greeting') {
				toasts.show(t('birthdays.greeting', { frame: e.greeting.frame_name, name: e.greeting.birthday_name }), {
					action: { label: t('common.review'), run: () => goto(`/feeds/${e.feed_id}?tab=birthdays`) },
					duration: 9000
				});
			} else if (e.type === 'birthday.today') void showBirthdaysToday(e.birthdays);
			// Google Photos imports (photo-sources.md §10): a dialog that follows the import shows it; otherwise a toast.
			// Family social (README §6.2): the badge, the chat list, reports, frame requests and milestones.
			else if (e.type === 'badge.updated') badge.set(e.badge);
			else if (e.type === 'report.created') {
				const feedId = e.report.feed_id;
				toasts.show(t('chat.newReport'), {
					action: { label: t('common.review'), run: () => goto(feedId ? `/feeds/${feedId}?tab=reports` : '/settings/reports') },
					duration: 9000
				});
			} else if (e.type === 'photo_request.created') {
				void frames.load(true);
				const r = e.request;
				toasts.show(t('engagement.wouldLikeToast', { frame: r.frame_name }), {
					action: { label: t('engagement.sendPhotos'), run: () => goto(requestSendUrl(r.frame_id, r.id)) },
					duration: 12000
				});
			} else if (e.type === 'photo_request.updated') {
				void frames.load(true);
				if (e.request.state === 'answered' && e.request.answered_by && e.request.answered_by.user_id !== auth.me?.id)
					toasts.show(t('engagement.answeredToast', { name: personName(e.request.answered_by, t('social.formerMember')), frame: e.request.frame_name }));
			} else if (e.type === 'milestone.achieved') {
				toasts.success(t(`engagement.milestone.${e.milestone.key}`));
			} else if (e.type === 'streak.updated') {
				const f = feeds.get(e.feed_id);
				if (f) feeds.upsert({ ...f, streak: e.streak });
			}
			else if (e.type === 'google_import.updated' && e.import.status === 'done' && !watching.has(e.import.id)) {
				toasts.show(tn('gimport.added', e.import.progress.imported), {
					action: { label: t('common.review'), run: () => goto(`/photo-sources?google_import=${e.import.id}`) },
					duration: 9000
				});
			}
		});
		const flush = () => toasts.flush();
		window.addEventListener('pagehide', flush);
		return () => {
			off();
			window.removeEventListener('pagehide', flush);
			live.stop();
		};
	});

	// Session side effects: live socket, frame list, guards.
	$effect(() => {
		if (auth.status === 'signed_in') {
			live.start(() => auth.getToken());
			void frames.load();
			void feeds.load();
			void push.init();
			badge.watchTitle();
			void badge.load();
			void chats.load();
		} else if (auth.status === 'signed_out') {
			live.stop();
			frames.reset();
			feeds.reset();
			// These read what they reset (the badge paints the title): untracked, or signed-out pages loop forever.
			untrack(() => {
				chats.reset();
				badge.reset();
			});
		}
	});

	$effect(() => {
		if (booting || auth.status !== 'signed_out' || isPublic) return;
		const next = page.url.pathname + page.url.search;
		void goto(`/?next=${encodeURIComponent(next)}`, { replaceState: true });
	});
</script>

{#if isGoogleReturn}
	{@render children()}
{:else if booting}
	<div class="splash" aria-busy="true">
		<Logo size={44} />
		<span class="visually-hidden">{t('common.loading')}</span>
	</div>
{:else if discovery.error}
	<div class="splash">
		<div class="card card-pad unreachable">
			<Icon name="wifi" size={28} />
			<h1>{t('boot.unreachableTitle')}</h1>
			<p class="muted">{t('boot.unreachableBody')}</p>
			<button class="btn btn-primary" onclick={boot}><Icon name="refresh" size={18} />{t('common.tryAgain')}</button>
		</div>
	</div>
{:else if discovery.storefront}
	{#if isStorefrontPath(page.url.pathname)}{@render children()}{/if}
{:else if isTv || isShop || isLegal || isConvert || isStation}
	{@render children()}
{:else if auth.status === 'signed_in' && page.url.pathname !== '/' && page.url.pathname !== '/callback'}
	<AppShell>{@render children()}</AppShell>
	<TvMiniBar />
{:else if isPublic}
	{@render children()}
{/if}

<Toaster />

<style>
	.splash {
		min-height: 100dvh;
		display: grid;
		place-items: center;
		padding: var(--s-4);
	}
	.splash :global(.logo) {
		animation: breathe 1.8s ease-in-out infinite;
	}
	@keyframes breathe {
		50% {
			opacity: 0.55;
			transform: scale(0.97);
		}
	}
	.unreachable {
		max-width: 420px;
		text-align: center;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-3);
	}
	.unreachable h1 {
		font-size: 1.5rem;
	}
</style>
