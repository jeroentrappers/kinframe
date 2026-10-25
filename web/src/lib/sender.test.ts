import { describe, expect, it } from 'vitest';
import { senderLabel } from './sender';

describe('senderLabel', () => {
	it('shows the sender, or the original sender of a Frameo import', () => {
		expect(senderLabel({ sender_name: 'Oma' })).toBe('Oma');
		expect(senderLabel({ sender_name: "Fien's frame", imported_from: 'frameo', imported_sender_name: 'Anna' })).toBe('Anna · via Frameo');
		expect(senderLabel({ sender_name: "Fien's frame", imported_from: 'frameo', imported_sender_name: ' ' })).toBe('via Frameo');
		expect(senderLabel({})).toBe('');
	});
});
