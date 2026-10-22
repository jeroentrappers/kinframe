<script lang="ts">
	import { caretAfterFormat, formatCode, isCompleteCode, normalizeCode } from '$lib/format';

	let {
		value = $bindable(''),
		id = 'frame-code',
		invalid = false,
		describedby,
		oncomplete,
		disabled = false
	}: {
		value?: string;
		id?: string;
		invalid?: boolean;
		describedby?: string;
		oncomplete?: (code: string) => void;
		disabled?: boolean;
	} = $props();

	let input: HTMLInputElement | undefined = $state();
	const display = $derived(formatCode(value));

	function handleInput(e: Event & { currentTarget: HTMLInputElement }) {
		const el = e.currentTarget;
		const raw = el.value;
		const caret = el.selectionStart ?? raw.length;
		const before = value;
		value = normalizeCode(raw);
		const formatted = formatCode(value);
		el.value = formatted;
		const pos = caretAfterFormat(raw, caret, formatted);
		el.setSelectionRange(pos, pos);
		if (isCompleteCode(value) && before !== value) oncomplete?.(value);
	}

	export function focus() {
		input?.focus();
	}
</script>

<input
	bind:this={input}
	{id}
	class="code-input"
	type="text"
	inputmode="numeric"
	autocomplete="one-time-code"
	enterkeyhint="go"
	placeholder="0000 0000"
	maxlength="12"
	spellcheck="false"
	aria-invalid={invalid}
	aria-describedby={describedby}
	{disabled}
	value={display}
	oninput={handleInput}
/>

<style>
	.code-input {
		width: 100%;
		max-width: 340px;
		min-height: 72px;
		padding: 0 20px;
		font-family: ui-monospace, 'SF Mono', 'Cascadia Mono', 'Roboto Mono', Menlo, monospace;
		font-size: 2.2rem;
		font-weight: 600;
		letter-spacing: 0.14em;
		text-align: center;
		font-variant-numeric: tabular-nums;
		border-radius: var(--r-lg);
		border: 2px solid var(--border-strong);
		background: var(--surface);
		color: var(--text);
		transition:
			border-color var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.code-input::placeholder {
		color: var(--surface-3);
	}
	.code-input:focus {
		outline: none;
		border-color: var(--primary);
		box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary) 20%, transparent);
	}
	.code-input[aria-invalid='true'] {
		border-color: var(--danger);
	}
	@media (max-width: 400px) {
		.code-input {
			font-size: 1.8rem;
			letter-spacing: 0.1em;
		}
	}
</style>
