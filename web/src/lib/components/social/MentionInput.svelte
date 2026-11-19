<script lang="ts" module>
	export interface MentionCandidate {
		user_id: string;
		display_name: string;
		avatar_url?: string | null;
	}
</script>

<script lang="ts">
	import { tick } from 'svelte';
	import type { Mention } from '$lib/api/client';
	import { insertMention, matchPeople, mentionQuery, reconcileMentions } from '$lib/social';
	import { t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';

	let {
		value = $bindable(''),
		mentions = $bindable<Mention[]>([]),
		people = [],
		label,
		placeholder = '',
		maxlength,
		disabled = false,
		rows = 1,
		maxMentions = 20,
		onsubmit,
		oninput,
		onkeydown: onkeydownExtra,
		textarea = $bindable()
	}: {
		value?: string;
		mentions?: Mention[];
		people?: MentionCandidate[];
		label: string;
		placeholder?: string;
		maxlength?: number;
		disabled?: boolean;
		rows?: number;
		maxMentions?: number;
		onsubmit?: () => void;
		oninput?: () => void;
		onkeydown?: (e: KeyboardEvent) => void;
		textarea?: HTMLTextAreaElement;
	} = $props();

	const uid = $props.id();
	let prev = value;
	let query = $state<{ start: number; query: string } | null>(null);
	let active = $state(0);
	const suggestions = $derived(query && mentions.length < maxMentions ? matchPeople(people, query.query) : []);
	const open = $derived(suggestions.length > 0);

	$effect(() => {
		// Value replaced from outside (cleared after sending, edit started): mentions came with it.
		if (value !== prev && document.activeElement !== textarea) prev = value;
	});

	function grow() {
		if (!textarea) return;
		textarea.style.height = 'auto';
		textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
	}
	$effect(() => {
		void value;
		grow();
	});

	function onInput() {
		mentions = reconcileMentions(prev, value, mentions);
		prev = value;
		updateQuery();
		oninput?.();
	}

	function updateQuery() {
		if (!textarea) return;
		const q = mentionQuery(value, textarea.selectionStart ?? value.length);
		query = q;
		active = 0;
	}

	async function pick(p: MentionCandidate) {
		if (!query || !textarea) return;
		const caret = textarea.selectionStart ?? value.length;
		const r = insertMention(value, mentions, query.start, caret, p);
		value = r.text;
		prev = r.text;
		mentions = r.mentions;
		query = null;
		await tick();
		textarea.focus();
		textarea.setSelectionRange(r.caret, r.caret);
	}

	function onKey(e: KeyboardEvent) {
		if (open) {
			if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
				e.preventDefault();
				active = (active + (e.key === 'ArrowDown' ? 1 : -1) + suggestions.length) % suggestions.length;
				return;
			}
			if (e.key === 'Enter' || e.key === 'Tab') {
				e.preventDefault();
				void pick(suggestions[active]!);
				return;
			}
			if (e.key === 'Escape') {
				e.preventDefault();
				query = null;
				return;
			}
		}
		onkeydownExtra?.(e);
		if (e.defaultPrevented) return;
		// Enter sends on a keyboard; Shift+Enter (and Enter on touch screens) makes a new line.
		if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && onsubmit && matchMedia('(pointer: fine)').matches) {
			e.preventDefault();
			onsubmit();
		}
	}
</script>

<div class="mention-input">
	<textarea
		bind:this={textarea}
		bind:value
		class="textarea"
		{rows}
		{placeholder}
		{maxlength}
		{disabled}
		aria-label={label}
		role="combobox"
		aria-autocomplete="list"
		aria-expanded={open}
		aria-controls="{uid}-list"
		aria-activedescendant={open ? `${uid}-opt-${active}` : undefined}
		oninput={onInput}
		onkeydown={onKey}
		onclick={updateQuery}
		onblur={() => setTimeout(() => (query = null), 150)}
	></textarea>
	{#if open}
		<ul id="{uid}-list" class="suggestions" role="listbox" aria-label={t('social.mentionPeople')}>
			{#each suggestions as p, i (p.user_id)}
				<li id="{uid}-opt-{i}" role="option" aria-selected={i === active}>
					<button type="button" tabindex="-1" class:active={i === active} onpointerdown={(e) => e.preventDefault()} onclick={() => pick(p)}>
						<Avatar name={p.display_name} url={p.avatar_url} size={26} />
						<span>{p.display_name}</span>
					</button>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.mention-input {
		position: relative;
		flex: 1;
		min-width: 0;
		display: flex;
	}
	textarea {
		width: 100%;
		resize: none;
		min-height: 44px;
		max-height: 200px;
		line-height: 1.4;
		padding-block: 10px;
	}
	.suggestions {
		position: absolute;
		z-index: 25;
		bottom: calc(100% + 6px);
		inset-inline-start: 0;
		min-width: 220px;
		max-width: 100%;
		list-style: none;
		margin: 0;
		padding: 4px;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r);
		box-shadow: var(--shadow-lg);
	}
	.suggestions button {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		width: 100%;
		min-height: 40px;
		padding: 4px 10px;
		border: 0;
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--text);
		font: inherit;
		font-weight: 600;
		text-align: start;
		cursor: pointer;
	}
	.suggestions button.active,
	.suggestions button:hover {
		background: var(--primary-soft);
	}
</style>
