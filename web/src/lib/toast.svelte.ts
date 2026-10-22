export type ToastKind = 'info' | 'success' | 'error';

export interface Toast {
	id: number;
	kind: ToastKind;
	message: string;
	action?: { label: string; run: () => void };
	duration: number;
}

let nextId = 1;

class Toasts {
	list = $state<Toast[]>([]);
	#timers = new Map<number, ReturnType<typeof setTimeout>>();
	#pending = new Map<number, () => void>();

	show(message: string, opts: { kind?: ToastKind; action?: Toast['action']; duration?: number } = {}): number {
		const id = nextId++;
		const toast: Toast = { id, message, kind: opts.kind ?? 'info', action: opts.action, duration: opts.duration ?? 5000 };
		this.list = [...this.list.slice(-3), toast];
		this.#timers.set(
			id,
			setTimeout(() => this.dismiss(id), toast.duration)
		);
		return id;
	}

	success(message: string) {
		return this.show(message, { kind: 'success' });
	}

	error(message: string) {
		return this.show(message, { kind: 'error', duration: 7000 });
	}

	dismiss(id: number) {
		const t = this.#timers.get(id);
		if (t) clearTimeout(t);
		this.#timers.delete(id);
		this.list = this.list.filter((x) => x.id !== id);
		const commit = this.#pending.get(id);
		this.#pending.delete(id);
		commit?.();
	}

	/**
	 * Undo instead of "are you sure": apply the change locally now, commit it to the server when
	 * the toast goes away, or roll back if the user taps Undo.
	 */
	deferred(opts: {
		message: string;
		undoLabel: string;
		apply: () => void;
		rollback: () => void;
		commit: () => Promise<unknown>;
		onCommitError?: (e: unknown) => void;
		duration?: number;
	}): number {
		opts.apply();
		let undone = false;
		const id = this.show(opts.message, {
			duration: opts.duration ?? 6000,
			action: {
				label: opts.undoLabel,
				run: () => {
					undone = true;
					this.#pending.delete(id);
					opts.rollback();
					this.dismiss(id);
				}
			}
		});
		this.#pending.set(id, () => {
			if (undone) return;
			opts.commit().catch((e) => {
				opts.rollback();
				opts.onCommitError?.(e);
			});
		});
		return id;
	}

	/** Commit everything still waiting (page is being hidden or closed). */
	flush() {
		for (const id of [...this.#pending.keys()]) this.dismiss(id);
	}
}

export const toasts = new Toasts();
