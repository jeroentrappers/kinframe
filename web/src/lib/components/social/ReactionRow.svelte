<script lang="ts">
	import { api, call, type PersonReaction, type ReactionSummary, type ReactionTargetKind } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { problemMessage } from '$lib/problem';
	import { PERSON_EMOJI, PERSON_REACTIONS, reactionLine, withMyReaction } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import ReactionsDialog from './ReactionsDialog.svelte';

	let {
		summary,
		kind,
		targetId,
		postId,
		size = 'md',
		canReact = true,
		showLine = true,
		onchange
	}: {
		summary: ReactionSummary | null | undefined;
		kind: ReactionTargetKind;
		targetId: string;
		/** Needed for `post_item`. */
		postId?: string;
		size?: 'sm' | 'md';
		canReact?: boolean;
		showLine?: boolean;
		onchange?: (s: ReactionSummary) => void;
	} = $props();

	let current = $state<ReactionSummary | null | undefined>(null);
	$effect.pre(() => {
		current = summary;
	});
	let pickerOpen = $state(false);
	let whoOpen = $state(false);
	let busy = $state(false);
	const uid = $props.id();

	const line = $derived(reactionLine(current, auth.me?.id, t('social.you')));
	const mine = $derived(current?.mine ?? null);
	const names = $derived(new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(line.names));

	async function react(emoji: PersonReaction | null) {
		pickerOpen = false;
		if (busy) return;
		const before = current;
		current = withMyReaction(current, emoji);
		busy = true;
		try {
			const s = await call(
				api.PUT('/v1/reactions/{targetKind}/{targetId}', {
					params: { path: { targetKind: kind, targetId }, query: postId ? { post_id: postId } : {} },
					body: { emoji }
				})
			);
			current = s;
			onchange?.(s);
		} catch (e) {
			current = before;
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	function onPickerKey(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			pickerOpen = false;
			document.getElementById(`${uid}-btn`)?.focus();
			return;
		}
		if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
		const buttons = [...(e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button')];
		const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
		const forward = (e.key === 'ArrowRight') !== (i18n.dir === 'rtl');
		buttons[(i + (forward ? 1 : -1) + buttons.length) % buttons.length]?.focus();
		e.preventDefault();
	}

	$effect(() => {
		if (!pickerOpen) return;
		const close = (e: PointerEvent) => {
			if (!(e.target as Element).closest(`[data-picker="${uid}"]`)) pickerOpen = false;
		};
		document.addEventListener('pointerdown', close);
		queueMicrotask(() => document.querySelector<HTMLButtonElement>(`[data-picker="${uid}"] .picker button`)?.focus());
		return () => document.removeEventListener('pointerdown', close);
	});
</script>

<div class="row {size}" data-picker={uid}>
	{#if canReact}
		<button
			id="{uid}-btn"
			class="react"
			class:on={!!mine}
			aria-haspopup="true"
			aria-expanded={pickerOpen}
			aria-label={mine ? t('social.reactedWith', { reaction: t(`social.reaction.${mine}`) }) : t('social.react')}
			onclick={() => (pickerOpen = !pickerOpen)}
			oncontextmenu={(e) => {
				e.preventDefault();
				void react(mine ? null : 'love');
			}}
		>
			{#if mine}<span class="emoji" aria-hidden="true">{PERSON_EMOJI[mine]}</span>{:else}<Icon name="smile" size={size === 'sm' ? 16 : 18} />{/if}
		</button>
		{#if pickerOpen}
			<!-- svelte-ignore a11y_interactive_supports_focus -->
			<div class="picker" role="toolbar" aria-label={t('social.react')} onkeydown={onPickerKey}>
				{#each PERSON_REACTIONS as r (r)}
					<button class:on={mine === r} aria-pressed={mine === r} title={t(`social.reaction.${r}`)} aria-label={t(`social.reaction.${r}`)} onclick={() => react(mine === r ? null : r)}>
						<span aria-hidden="true">{PERSON_EMOJI[r]}</span>
					</button>
				{/each}
			</div>
		{/if}
	{/if}
	{#if showLine && line.total > 0}
		<button class="line" onclick={() => (whoOpen = true)} aria-label={t('social.whoReacted')}>
			<span class="emojis" aria-hidden="true">{line.emojis.join('')}</span>
			<span class="names">
				{#if line.names.length === 0}
					<span class="num">{line.total}</span>
				{:else if line.others > 0}
					{tn('social.reactedOthers', line.others, { names })}
				{:else}
					{names}
				{/if}
			</span>
		</button>
	{/if}
</div>

{#if whoOpen}
	<ReactionsDialog bind:open={whoOpen} {kind} {targetId} {postId} />
{/if}

<style>
	.row {
		position: relative;
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-width: 0;
	}
	.react {
		flex: none;
		display: inline-grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border-radius: var(--r-full);
		border: 1px solid var(--border);
		background: var(--surface);
		color: var(--text-muted);
		cursor: pointer;
		transition:
			background var(--dur) var(--ease),
			transform var(--dur) var(--ease);
	}
	.sm .react {
		width: 30px;
		height: 30px;
	}
	.react:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.react.on {
		background: var(--primary-soft);
		border-color: transparent;
	}
	.react .emoji {
		font-size: 1.05rem;
		line-height: 1;
	}
	.picker {
		position: absolute;
		z-index: 20;
		bottom: calc(100% + 6px);
		inset-inline-start: 0;
		display: flex;
		gap: 2px;
		padding: 4px;
		border-radius: var(--r-full);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-lg);
		animation: pop 160ms var(--ease);
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(4px) scale(0.96);
		}
	}
	.picker button {
		width: 40px;
		height: 40px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		font-size: 1.35rem;
		line-height: 1;
		cursor: pointer;
		transition: transform 120ms var(--ease);
	}
	.picker button:hover,
	.picker button:focus-visible {
		transform: scale(1.18);
		background: var(--surface-2);
	}
	.picker button.on {
		background: var(--primary-soft);
	}
	.line {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		min-width: 0;
		padding: 4px 6px;
		border: 0;
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--text-muted);
		font: inherit;
		font-size: 0.88rem;
		cursor: pointer;
		text-align: start;
	}
	.sm .line {
		font-size: 0.8rem;
	}
	.line:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.emojis {
		letter-spacing: -2px;
		flex: none;
	}
	.names {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
