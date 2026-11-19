<script lang="ts" module>
	export interface Attachment {
		key: string;
		file: File;
		url: string;
		kind: 'photo' | 'video';
		progress: number;
		uploadId: string | null;
		error: boolean;
	}
</script>

<script lang="ts">
	import { api, call, type LinkPreview, type Mention, type Message } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { mediaKindOf } from '$lib/logic';
	import { codePoints, firstLink, personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { uploadFile } from '$lib/upload';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import MentionInput, { type MentionCandidate } from '../social/MentionInput.svelte';
	import VoiceRecorder from './VoiceRecorder.svelte';

	let {
		people,
		replyTo = $bindable<Message | null>(null),
		editing = $bindable<Message | null>(null),
		disabled = false,
		onsend,
		onedit,
		ontyping,
		onvoice
	}: {
		people: MentionCandidate[];
		replyTo?: Message | null;
		editing?: Message | null;
		disabled?: boolean;
		onsend: (m: { body: string; mentions: Mention[]; uploadIds: string[]; linkPreview: boolean }) => Promise<boolean>;
		onedit: (m: Message, body: string, mentions: Mention[]) => Promise<boolean>;
		ontyping: (activity: 'typing' | 'recording') => void;
		onvoice: (v: { upload_id: string; duration_ms: number }) => Promise<void>;
	} = $props();

	let body = $state('');
	let mentions = $state<Mention[]>([]);
	let attachments = $state<Attachment[]>([]);
	let sending = $state(false);
	let recording = $state(false);
	let fileInput: HTMLInputElement | undefined = $state();
	let input: HTMLTextAreaElement | undefined = $state();
	const limits = $derived(discovery.limits);
	const length = $derived(codePoints(body.trim()));
	const uploading = $derived(attachments.some((a) => !a.uploadId && !a.error));
	const canSend = $derived(!sending && !uploading && !disabled && length <= limits.max_message_chars && (length > 0 || attachments.some((a) => a.uploadId)));

	// Editing a message puts its text in the box.
	let editingId: string | null = null;
	$effect(() => {
		const e = editing;
		if (e && e.id !== editingId) {
			editingId = e.id;
			body = e.body ?? '';
			mentions = [...(e.mentions ?? [])];
			input?.focus();
		} else if (!e && editingId) {
			editingId = null;
			body = '';
			mentions = [];
		}
	});
	$effect(() => {
		if (replyTo) input?.focus();
	});

	// ---- attachments: the normal tus uploads, at most limits.max_message_items
	function addFiles(files: File[]) {
		const room = limits.max_message_items - attachments.length;
		const ok = files.filter((f) => mediaKindOf(f) !== null).slice(0, Math.max(0, room));
		if (files.length > ok.length) toasts.show(t('chat.tooManyFiles', { max: limits.max_message_items }));
		for (const f of ok) {
			const a: Attachment = { key: crypto.randomUUID(), file: f, url: URL.createObjectURL(f), kind: mediaKindOf(f)!, progress: 0, uploadId: null, error: false };
			attachments = [...attachments, a];
			const h = uploadFile(f, { onProgress: (s, total) => patch(a.key, (x) => (x.progress = total ? s / total : 0)) });
			h.promise.then((id) => patch(a.key, (x) => (x.uploadId = id))).catch(() => patch(a.key, (x) => (x.error = true)));
		}
	}
	function patch(key: string, fn: (a: Attachment) => void) {
		const a = attachments.find((x) => x.key === key);
		if (a) fn(a);
	}
	function removeAttachment(key: string) {
		const a = attachments.find((x) => x.key === key);
		if (a) URL.revokeObjectURL(a.url);
		attachments = attachments.filter((x) => x.key !== key);
	}

	// ---- link preview (operator opt-in): the composer shows it before sending
	let preview = $state<LinkPreview | null>(null);
	let previewOff = $state<string | null>(null);
	let previewTimer: ReturnType<typeof setTimeout> | null = null;
	$effect(() => {
		if (!discovery.linkPreviews) return;
		const link = firstLink(body);
		if (previewTimer) clearTimeout(previewTimer);
		if (!link || link === previewOff) {
			if (!link) preview = null;
			return;
		}
		if (preview?.url === link) return;
		previewTimer = setTimeout(() => {
			call(api.POST('/v1/link-previews', { body: { url: link } }))
				.then((p) => firstLink(body) === link && (preview = p))
				.catch(() => (preview = null));
		}, 600);
	});

	let lastTyping = 0;
	function typed() {
		const now = Date.now();
		if (now - lastTyping > 3000 && body.trim()) {
			lastTyping = now;
			ontyping('typing');
		}
	}

	async function submit() {
		if (!canSend) return;
		sending = true;
		try {
			if (editing) {
				if (await onedit(editing, body, mentions)) editing = null;
				return;
			}
			const ok = await onsend({
				body,
				mentions,
				uploadIds: attachments.filter((a) => a.uploadId).map((a) => a.uploadId!),
				linkPreview: !(previewOff && firstLink(body) === previewOff)
			});
			if (ok) {
				body = '';
				mentions = [];
				for (const a of attachments) URL.revokeObjectURL(a.url);
				attachments = [];
				preview = null;
				previewOff = null;
				replyTo = null;
			}
		} finally {
			sending = false;
		}
	}

	function onPaste(e: ClipboardEvent) {
		const files = Array.from(e.clipboardData?.files ?? []);
		if (files.length) {
			e.preventDefault();
			addFiles(files);
		}
	}
</script>

<div class="composer">
	{#if replyTo || editing}
		<div class="context small">
			<Icon name={editing ? 'edit' : 'reply'} size={14} />
			<span class="ctx-text">
				{#if editing}{t('chat.editing')}{:else if replyTo}{t('chat.replyingTo', { name: personName(replyTo.author, t('social.formerMember')) })}: <span class="muted">{replyTo.body ?? (replyTo.voice ? t('chat.voiceMessage') : t('chat.photo'))}</span>{/if}
			</span>
			<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('common.cancel')} onclick={() => ((replyTo = null), (editing = null))}><Icon name="x" size={14} /></button>
		</div>
	{/if}
	{#if attachments.length}
		<ul class="atts" role="list" aria-label={tn('chat.attachments', attachments.length)}>
			{#each attachments as a (a.key)}
				<li class:err={a.error}>
					{#if a.kind === 'video'}<video src={a.url} muted playsinline></video>{:else}<img src={a.url} alt="" />{/if}
					{#if !a.uploadId && !a.error}<span class="prog" style="--p:{a.progress}" aria-hidden="true"></span>{/if}
					{#if a.error}<span class="att-err" title={t('chat.uploadFailed')}><Icon name="alert" size={16} /></span>{/if}
					<button class="rm" aria-label={t('chat.removeAttachment')} onclick={() => removeAttachment(a.key)}><Icon name="x" size={12} /></button>
				</li>
			{/each}
		</ul>
	{/if}
	{#if preview && preview.status === 'ok' && previewOff !== preview.url}
		<div class="lp small">
			<Icon name="link" size={14} />
			<span class="lp-t"><strong>{preview.title ?? preview.site_name ?? preview.url}</strong>{#if preview.description}<span class="muted"> · {preview.description}</span>{/if}</span>
			<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('chat.removePreview')} onclick={() => (previewOff = preview?.url ?? null)}><Icon name="x" size={14} /></button>
		</div>
	{/if}
	<div class="bar">
		{#if recording}
			<VoiceRecorder
				onactivity={() => ontyping('recording')}
				oncancel={() => (recording = false)}
				onsend={async (v) => {
					await onvoice(v);
					recording = false;
				}}
			/>
		{:else}
			{#if !editing}
				<input bind:this={fileInput} type="file" accept="image/*,video/*,.heic,.heif" multiple hidden onchange={(e) => {
					addFiles(Array.from(e.currentTarget.files ?? []));
					e.currentTarget.value = '';
				}} data-testid="chat-file-input" />
				<button class="btn btn-ghost btn-icon" onclick={() => fileInput?.click()} disabled={disabled} aria-label={t('chat.addPhotos')}><Icon name="image" size={20} /></button>
			{/if}
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div class="text" onpaste={onPaste}>
				<MentionInput bind:value={body} bind:mentions bind:textarea={input} {people} label={t('chat.writeMessage')} placeholder={disabled ? t('chat.readOnly') : t('chat.writeMessage')} {disabled} maxMentions={limits.max_mentions} onsubmit={submit} oninput={typed} onkeydown={(e) => {
					if (e.key === 'Escape' && (replyTo || editing)) {
						replyTo = null;
						editing = null;
					}
				}} />
			</div>
			{#if discovery.voiceMessages && !editing && !body.trim() && attachments.length === 0}
				<button class="btn btn-ghost btn-icon" onclick={() => (recording = true)} disabled={disabled} aria-label={t('chat.recordVoice')}><Icon name="mic" size={20} /></button>
			{:else}
				<button class="btn btn-primary btn-icon send" onclick={submit} disabled={!canSend} aria-label={editing ? t('common.save') : t('social.send')}>
					{#if sending}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={editing ? 'check' : 'send'} size={18} />{/if}
				</button>
			{/if}
		{/if}
	</div>
	{#if length > limits.max_message_chars - 200}
		<p class="counter small num" class:over={length > limits.max_message_chars}>{length}/{limits.max_message_chars}</p>
	{/if}
</div>

<style>
	.composer {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: var(--s-2);
		background: var(--surface);
		border-top: 1px solid var(--border);
	}
	.context,
	.lp {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 4px 8px;
		border-radius: var(--r-sm);
		background: var(--surface-2);
		min-width: 0;
	}
	.ctx-text,
	.lp-t {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.bar {
		display: flex;
		align-items: flex-end;
		gap: 4px;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: flex;
	}
	.send {
		flex: none;
	}
	.atts {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		gap: 6px;
		overflow-x: auto;
	}
	.atts li {
		position: relative;
		width: 64px;
		height: 64px;
		flex: none;
		border-radius: var(--r-sm);
		overflow: hidden;
		background: var(--surface-2);
	}
	.atts li.err {
		outline: 2px solid var(--danger);
	}
	.atts img,
	.atts video {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.prog {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: 4px;
		background: linear-gradient(to right, var(--primary) calc(var(--p) * 100%), rgb(0 0 0 / 0.25) 0);
	}
	.att-err {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.35);
		color: #fff;
	}
	.rm {
		position: absolute;
		top: 3px;
		inset-inline-end: 3px;
		width: 20px;
		height: 20px;
		border-radius: 50%;
		border: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.6);
		color: #fff;
		cursor: pointer;
	}
	.counter {
		margin: 0;
		text-align: end;
		color: var(--text-muted);
	}
	.counter.over {
		color: var(--danger);
	}
</style>
