import { describe, expect, it } from 'vitest';
import { javaHash, kenBurnsAt, kenBurnsKeyframes, kenBurnsKeys, MAX_ZOOM } from './kenburns';

// Values printed by the Android frame's KenBurns.params (frame/slideshow/KenBurns.kt): a TV
// mirroring a frame must move each photo the same way.
const FRAME = {
	'0192f0a0-0001-7000-8000-000000000001': {
		focal: [null, null] as const,
		keys: <[number, number, number][]>[
			[1.0491173, 3.57029e-4, 0.013119923],
			[1.2795491, 0.014695468, 0.12294898],
			[1.0548323, -0.0066808686, -0.0036929515],
			[1.3130368, 0.0027895654, 0.121895656],
			[1.0480356, 0.0022751691, 0.004487511],
			[1.263837, -0.07779988, 0.07798749]
		],
		at25: <[number, number, number]>[1.1864524, -0.0032495228, 0.058524247]
	},
	abc: {
		focal: [0.3, 0.7] as const,
		keys: <[number, number, number][]>[
			[1.0466868, -9.988977e-4, 7.019267e-5],
			[1.2814072, 0.11256289, -0.11256289],
			[1.0483168, 0.0020358388, -0.004301794],
			[1.2607396, -0.038751908, 0.09506517],
			[1.0207868, 0.0073731607, -0.0014340595],
			[1.3051397, 0.0986835, -0.0036625226]
		],
		at25: <[number, number, number]>[1.1549572, -0.028148793, 0.058179207]
	}
};

describe('Ken Burns', () => {
	it('matches the frame for the same placement and focal point', () => {
		expect(javaHash('abc')).toBe(96354);
		for (const [id, f] of Object.entries(FRAME)) {
			const keys = kenBurnsKeys(id, f.focal[0], f.focal[1]);
			keys.forEach((k, i) => {
				const [s, x, y] = f.keys[i]!;
				expect(k.scale).toBeCloseTo(s, 4);
				expect(k.x).toBeCloseTo(x, 4);
				expect(k.y).toBeCloseTo(y, 4);
			});
			const a = kenBurnsAt(keys, 2.5);
			expect(a.scale).toBeCloseTo(f.at25[0], 4);
			expect(a.x).toBeCloseTo(f.at25[1], 4);
			expect(a.y).toBeCloseTo(f.at25[2], 4);
		}
	});

	it('never shows an edge and keeps moving', () => {
		const keys = kenBurnsKeys('x', null, null);
		for (let t = 0; t < 12; t += 0.1) {
			const k = kenBurnsAt(keys, t);
			expect(k.scale).toBeGreaterThanOrEqual(1);
			expect(k.scale).toBeLessThanOrEqual(MAX_ZOOM);
			expect(Math.abs(k.x)).toBeLessThanOrEqual((k.scale - 1) / 2 + 1e-9);
		}
		const frames = kenBurnsKeyframes(keys, 45_000);
		expect(frames[0]!.offset).toBe(0);
		expect(frames[frames.length - 1]!.offset).toBe(1);
		expect(frames.length).toBeGreaterThan(10);
	});
});
