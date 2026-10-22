import { describe, expect, it } from 'vitest';
import { shareCoverage, shareSource } from './share';

describe('shareCoverage', () => {
	it('tells which frames already have all, some or none of the selection', () => {
		const skipped = [
			{ placement_id: 'p1', frame_id: 'A' },
			{ placement_id: 'p2', frame_id: 'A' },
			{ placement_id: 'p1', frame_id: 'B' }
		];
		expect(shareCoverage(skipped, ['A', 'B', 'C'], 2)).toEqual({ A: 'all', B: 'some', C: 'none' });
	});

	it('counts a placement once per frame', () => {
		const skipped = [
			{ placement_id: 'p1', frame_id: 'A' },
			{ placement_id: 'p1', frame_id: 'A' }
		];
		expect(shareCoverage(skipped, ['A'], 2)).toEqual({ A: 'some' });
	});
});

describe('shareSource', () => {
	const frames = [
		{ frame_id: 'A', placements: [{ placement_id: 'a1', item_id: 'i1', status: 'removed' }] },
		{
			frame_id: 'B',
			placements: [
				{ placement_id: 'b1', item_id: 'i1', status: 'seen' },
				{ placement_id: 'b2', item_id: 'i2', status: 'pending' }
			]
		}
	];

	it('picks the first frame that still holds every item', () => {
		expect(shareSource(frames, ['i1'])).toEqual({ frameId: 'B', placementIds: ['b1'] });
		expect(shareSource(frames, ['i1', 'i2'])).toEqual({ frameId: 'B', placementIds: ['b1', 'b2'] });
	});

	it('returns null when no frame has them all', () => {
		expect(shareSource(frames, ['i3'])).toBeNull();
		expect(shareSource(frames, [])).toBeNull();
	});
});
