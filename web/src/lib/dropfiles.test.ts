import { describe, expect, it } from 'vitest';
import { carriesFiles, DragDepth, dropFrameAt, filesFromDataTransfer } from './dropfiles';

/** A stand-in for a DOM element: `closest` walks up the parents like the real thing (attribute selectors only). */
interface FakeEl {
	attrs: Record<string, string>;
	parent: FakeEl | null;
	getAttribute(name: string): string | null;
	closest(selector: string): FakeEl | null;
}

function el(attrs: Record<string, string> = {}, parent: FakeEl | null = null): FakeEl {
	const node: FakeEl = {
		attrs,
		parent,
		getAttribute: (name) => attrs[name] ?? null,
		closest(selector) {
			const name = selector.replace(/^\[|\]$/g, '');
			for (let n: FakeEl | null = node; n; n = n.parent) if (name in n.attrs) return n;
			return null;
		}
	};
	return node;
}

describe('file drags', () => {
	it('only counts drags that carry files from the computer', () => {
		expect(carriesFiles({ types: ['Files'] })).toBe(true);
		expect(carriesFiles({ types: ['text/plain', 'Files'] })).toBe(true);
		expect(carriesFiles({ types: ['text/uri-list', 'text/html'] })).toBe(false);
		expect(carriesFiles(null)).toBe(false);
		expect(carriesFiles(undefined)).toBe(false);
	});

	it('finds the frame a drop is aimed at from any element inside its card', () => {
		const card = el({ 'data-drop-frame': 'f1' });
		const title = el({}, el({}, card));
		expect(dropFrameAt(title as unknown as EventTarget)).toBe('f1');
		expect(dropFrameAt(card as unknown as EventTarget)).toBe('f1');
		expect(dropFrameAt(el({}, el()) as unknown as EventTarget)).toBeNull();
		expect(dropFrameAt(el({ 'data-drop-frame': '' }) as unknown as EventTarget)).toBeNull();
		expect(dropFrameAt(null)).toBeNull();
		// The window and the document have no `closest`.
		expect(dropFrameAt({} as EventTarget)).toBeNull();
	});

	it('stays active while the pointer moves between nested elements', () => {
		const d = new DragDepth();
		expect(d.active).toBe(false);
		expect(d.enter()).toBe(true); // page
		expect(d.enter()).toBe(true); // a card inside it…
		expect(d.leave()).toBe(true); // …fires before the page's leave
		expect(d.leave()).toBe(false); // left the window
		expect(d.leave()).toBe(false); // never below zero
		d.enter();
		d.reset();
		expect(d.active).toBe(false);
	});

	it('falls back to the plain file list when entries are unavailable', async () => {
		const a = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
		const dt = { items: [{ kind: 'file', webkitGetAsEntry: () => null }], files: [a] } as unknown as DataTransfer;
		expect(await filesFromDataTransfer(dt)).toEqual([a]);
	});
});
