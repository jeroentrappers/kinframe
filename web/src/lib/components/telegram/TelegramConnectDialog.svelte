<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type TelegramChat, type TelegramCode } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import type { ChannelTarget } from '$lib/emailInbox';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { botHandle, codeBody, codeErrorText, codeLink, minutesLeft, type CodeKind } from '$lib/telegram';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import QrCode from '../QrCode.svelte';

	/**
	 * Connect a chat (telegram.md §5.1): creates a one-time code, shows its t.me deep link as a button and a QR code,
	 * and follows it (`telegram_code.updated`, else polling) until Telegram used it. `kind: 'link'` is "Link my
	 * Telegram" in account settings.
	 */
	let {
		open = $bindable(false),
		target = null,
		kind,
		onconnected,
		onlinked
	}: {
		open?: boolean;
		target?: ChannelTarget | null;
		kind: CodeKind;
		onconnected?: (chatId: string) => void;
		onlinked?: () => void;
	} = $props();

	let code = $state<TelegramCode | null>(null);
	let link = $state<string | null>(null);
	let error = $state<unknown>(null);
	let creating = $state(false);
	let chat = $state<TelegramChat | null>(null);
	let now = $state(Date.now());

	const bot = $derived(discovery.telegramBot);
	const name = $derived(target?.name ?? '');
	const left = $derived(code ? minutesLeft(code.expires_at, new Date(now)) : 0);
	const errText = $derived(code ? codeErrorText(code.error_code, name) : null);
	const title = $derived(
		kind === 'group' ? t('telegram.connectGroup') : kind === 'private_self' ? t('telegram.sendFromMine') : kind === 'private_invite' ? t('telegram.inviteSomeone') : t('telegram.linkTitle')
	);

	async function create() {
		creating = true;
		error = null;
		code = null;
		chat = null;
		link = null;
		try {
			const opts = { header: { 'Idempotency-Key': crypto.randomUUID() } };
			let c: TelegramCode;
			if (kind === 'link') c = await call(api.POST('/v1/me/telegram/link-code', { params: opts }));
			else if (target?.kind === 'feed')
				c = await call(api.POST('/v1/feeds/{feedId}/telegram-codes', { params: { path: { feedId: target.id }, ...opts }, body: codeBody(kind) }));
			else c = await call(api.POST('/v1/frames/{frameId}/telegram-codes', { params: { path: { frameId: target!.id }, ...opts }, body: codeBody(kind) }));
			code = c;
			link = codeLink(c, bot);
		} catch (e) {
			error = e;
		} finally {
			creating = false;
		}
	}

	async function apply(c: TelegramCode) {
		if (!code || c.id !== code.id) return;
		code = { ...code, ...c, url: code.url, code: code.code };
		if (c.status !== 'used') return;
		if (kind === 'link' || (kind === 'private_self' && !c.chat_id)) onlinked?.();
		if (c.chat_id && !chat) {
			try {
				chat = await call(api.GET('/v1/telegram-chats/{chatId}', { params: { path: { chatId: c.chat_id } } }));
			} catch {
				/* the section reloads anyway */
			}
			onconnected?.(c.chat_id);
		}
	}

	$effect(() => {
		if (!open) return;
		untrack(() => void create());
		const off = live.on((e) => {
			if (e.type === 'telegram_code.updated') void apply(e.code);
		});
		// Polling as a fallback for a closed websocket; also ticks the "minutes left".
		const timer = setInterval(async () => {
			now = Date.now();
			const c = code;
			if (!c || c.status !== 'open') return;
			try {
				await apply(await call(api.GET('/v1/telegram-codes/{codeId}', { params: { path: { codeId: c.id } } })));
			} catch (e) {
				if (isCode(e, 'not_found')) code = { ...c, status: 'revoked' };
			}
		}, 4000);
		return () => {
			off();
			clearInterval(timer);
		};
	});

	async function copy() {
		if (!link) return;
		try {
			await navigator.clipboard.writeText(link);
			toasts.success(t('telegram.linkCopied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}

	async function share() {
		if (!link) return;
		const text = t('telegram.inviteText', { name });
		if (navigator.share) {
			try {
				await navigator.share({ title: t('telegram.inviteTitle', { name }), text, url: link });
			} catch {
				/* cancelled */
			}
		} else void copy();
	}
</script>

<Dialog bind:open {title} size="md">
	<div class="stack connect" data-testid="telegram-connect">
		{#if kind === 'group'}
			<p>{target?.kind === 'feed' ? t('telegram.groupExplainFeed', { name }) : t('telegram.groupExplainFrame', { name })}</p>
			{#if discovery.telegramPrivacyMode}<p class="callout callout-warn small"><Icon name="info" size={18} />{t('telegram.privacyUpFront')}</p>{/if}
		{:else if kind === 'private_self'}
			<p>{t('telegram.selfExplain', { name, bot: botHandle(bot) })}</p>
		{:else if kind === 'private_invite'}
			<p>{t('telegram.inviteExplain', { name })}</p>
		{:else}
			<p>{t('telegram.linkExplain', { bot: botHandle(bot) })}</p>
		{/if}

		{#if creating}
			<div class="skeleton" style="height:180px"></div>
		{:else if error}
			<div class="callout callout-error small" role="alert">
				<Icon name="alert" size={18} />
				<div class="stack-sm">
					<span>{isCode(error, 'telegram_already_linked') ? t('problem.telegram_already_linked') : isCode(error, 'limit_reached') ? t('telegram.codeErr.limit_reached', { name }) : problemMessage(error)}</span>
					<button class="btn btn-sm" onclick={create}>{t('common.tryAgain')}</button>
				</div>
			</div>
		{:else if code}
			{#if code.status === 'used'}
				<p class="callout callout-ok done" role="status" data-testid="telegram-done">
					<Icon name="check" size={20} />
					{#if chat}{t('telegram.connectedTo', { name: chat.title })}{:else if kind === 'link'}{t('telegram.linkedDone')}{:else}{t('telegram.connectedDone')}{/if}
				</p>
			{:else if code.status === 'open'}
				<div class="link-area">
					<div class="stack-sm grow">
						{#if link}
							<a class="btn btn-primary btn-lg" href={link} target="_blank" rel="noopener noreferrer" data-testid="telegram-open"><Icon name="plane" size={18} />{t('telegram.openTelegram')}</a>
							<div class="row-wrap">
								<button class="btn btn-sm" onclick={copy}><Icon name="copy" size={16} />{t('telegram.copyLink')}</button>
								{#if kind === 'private_invite'}<button class="btn btn-sm" onclick={share}><Icon name="share" size={16} />{t('email.share')}</button>{/if}
							</div>
							<code class="url">{link}</code>
						{/if}
						<p class="status small" role="status"><span class="spinner" aria-hidden="true"></span>{t('telegram.waiting')}</p>
						<p class="faint tiny">{t('telegram.codeValid', { minutes: left })}</p>
						{#if errText}<p class="callout callout-warn small" role="alert"><Icon name="alert" size={18} />{errText}</p>{/if}
						{#if kind === 'group'}<p class="faint tiny">{t('telegram.groupSteps')}</p>{/if}
					</div>
					{#if link}
						<figure class="qr">
							<QrCode value={link} size={176} label={t('telegram.qrLabel')} />
							<figcaption class="faint tiny">{t('telegram.qrHint')}</figcaption>
						</figure>
					{/if}
				</div>
			{:else}
				<div class="callout callout-warn small" role="alert">
					<Icon name="clock" size={18} />
					<div class="stack-sm">
						<span>{errText ?? t('telegram.codeErr.code_expired')}</span>
						<button class="btn btn-sm" onclick={create}>{t('telegram.newLink')}</button>
					</div>
				</div>
			{/if}
		{/if}
	</div>
	{#snippet footer()}
		<button class="btn {code?.status === 'used' ? 'btn-primary' : 'btn-ghost'}" onclick={() => (open = false)}>{code?.status === 'used' ? t('common.done') : t('common.close')}</button>
	{/snippet}
</Dialog>

<style>
	.connect p {
		margin: 0;
	}
	.link-area {
		display: flex;
		gap: var(--s-5);
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.grow {
		flex: 1;
		min-width: 220px;
	}
	.btn-lg {
		align-self: flex-start;
	}
	.url {
		font-family: ui-monospace, monospace;
		font-size: 0.78rem;
		color: var(--text-muted);
		overflow-wrap: anywhere;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.qr {
		margin: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		max-width: 200px;
		text-align: center;
	}
	.done {
		font-weight: 600;
	}
</style>
