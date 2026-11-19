<script lang="ts" generics="V extends string">
	/** Radio options as cards with a hint each (the leave sheet, keep answers, account deletion). */
	let {
		legend,
		value = $bindable(),
		options,
		name
	}: { legend: string; value: V; options: { id: V; label: string; hint?: string }[]; name: string } = $props();
</script>

<fieldset class="choices">
	<legend class="label">{legend}</legend>
	{#each options as o (o.id)}
		<label class="opt" class:checked={value === o.id}>
			<input type="radio" {name} value={o.id} checked={value === o.id} onchange={() => (value = o.id)} />
			<span><strong>{o.label}</strong>{#if o.hint}<span class="hint">{o.hint}</span>{/if}</span>
		</label>
	{/each}
</fieldset>

<style>
	.choices {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.opt {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 10px 12px;
		border: 1.5px solid var(--border);
		border-radius: var(--r);
		cursor: pointer;
	}
	.opt.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.opt > span {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.opt input {
		margin-top: 4px;
		flex: none;
	}
</style>
