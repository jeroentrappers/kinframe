import { describe, expect, it } from 'vitest';
import {
	MESSAGE_TYPE,
	PICKER_TYPE,
	backUrl,
	canCancel,
	importDetails,
	importHeadline,
	importMessage,
	importProgress,
	isFinal,
	isGoogleUrl,
	parseReturn,
	pickerUrl,
	readImportMessage,
	readPickerMessage,
	resumeId,
	returnUri
} from './googleImport';

const ID = '0192f0a0-0020-7000-8000-000000000001';
const ORIGIN = 'https://kinframe.example.org';
const progress = (p: Partial<{ picked: number; imported: number; duplicates: number; skipped: number; failed: number }>) => ({ picked: 0, imported: 0, ...p });

describe('return_uri', () => {
	it('is the SPA return page on this origin', () => {
		expect(returnUri('https://kinframe.example.org/')).toBe('https://kinframe.example.org/google-import/return');
	});

	it('parses the import id and status the callback adds, nothing else', () => {
		expect(parseReturn(new URLSearchParams(`import_id=${ID}&status=picking`))).toEqual({ importId: ID, status: 'picking' });
		expect(parseReturn(new URLSearchParams(`import_id=${ID}&status=failed`))).toEqual({ importId: ID, status: 'failed' });
		expect(parseReturn(new URLSearchParams('import_id=../x&status=picking'))).toBeNull();
		expect(parseReturn(new URLSearchParams(`import_id=${ID}&status=owned`))).toBeNull();
		expect(parseReturn(new URLSearchParams(''))).toBeNull();
	});

	it('goes back to a stored same-origin path only', () => {
		expect(backUrl('/feeds/abc', ID)).toBe(`/feeds/abc?google_import=${ID}`);
		expect(backUrl('/frames/f?tab=library&album=a', ID)).toBe(`/frames/f?tab=library&album=a&google_import=${ID}`);
		expect(backUrl('//evil.example/x', ID)).toBe(`/photo-sources?google_import=${ID}`);
		expect(backUrl('https://evil.example/', ID)).toBe(`/photo-sources?google_import=${ID}`);
		expect(backUrl(null, ID)).toBe(`/photo-sources?google_import=${ID}`);
		expect(resumeId(new URLSearchParams(`google_import=${ID}`))).toBe(ID);
		expect(resumeId(new URLSearchParams('google_import=<x>'))).toBeNull();
	});
});

describe('messages between the popup and the page', () => {
	it('accepts only our shape from our origin', () => {
		const msg = importMessage(ID, 'picking');
		expect(msg).toEqual({ type: MESSAGE_TYPE, import_id: ID, status: 'picking' });
		expect(readImportMessage({ origin: ORIGIN, data: msg }, ORIGIN)).toEqual(msg);
		expect(readImportMessage({ origin: 'https://evil.example', data: msg }, ORIGIN)).toBeNull();
		expect(readImportMessage({ origin: ORIGIN, data: { ...msg, status: 'nope' } }, ORIGIN)).toBeNull();
		expect(readImportMessage({ origin: ORIGIN, data: 'hello' }, ORIGIN)).toBeNull();
	});

	it('only goes on to Google’s picker, with /autoclose', () => {
		const answer = { type: PICKER_TYPE, import_id: ID, picker_uri: 'https://photos.google.com/picker/abc' };
		expect(readPickerMessage(answer, ID)).toBe('https://photos.google.com/picker/abc/autoclose');
		expect(readPickerMessage({ ...answer, picker_uri: null }, ID)).toBeNull();
		expect(readPickerMessage({ ...answer, picker_uri: 'https://evil.example/picker' }, ID)).toBeNull();
		expect(readPickerMessage({ ...answer, import_id: 'other-0000-0000' }, ID)).toBeUndefined();
		expect(readPickerMessage({ type: 'x' }, ID)).toBeUndefined();
		expect(pickerUrl('https://photos.google.com/picker/abc/')).toBe('https://photos.google.com/picker/abc/autoclose');
		expect(pickerUrl('https://photos.google.com/picker/abc/autoclose')).toBe('https://photos.google.com/picker/abc/autoclose');
		expect(isGoogleUrl('https://accounts.google.com/o/oauth2/v2/auth?x=1')).toBe(true);
		expect(isGoogleUrl('https://google.com.evil.example/')).toBe(false);
		expect(isGoogleUrl('http://accounts.google.com/')).toBe(false);
	});
});

describe('import state', () => {
	it('knows final states and what can be cancelled', () => {
		expect(['done', 'failed', 'cancelled', 'expired'].every((s) => isFinal(s as never))).toBe(true);
		expect(isFinal('importing')).toBe(false);
		expect(canCancel('picking')).toBe(true);
		expect(canCancel('done')).toBe(false);
	});

	it('shows progress and a summary', () => {
		expect(importProgress({ status: 'importing', progress: progress({ picked: 23, imported: 9 }) })).toBeCloseTo(9 / 23);
		expect(importProgress({ status: 'done', progress: progress({ picked: 23, imported: 23 }) })).toBeNull();
		expect(importHeadline({ status: 'importing', progress: progress({ picked: 23, imported: 8, duplicates: 1 }) })).toBe('Adding photos from Google Photos… 9 of 23');
		expect(importHeadline({ status: 'done', progress: progress({ picked: 23, imported: 21, duplicates: 2 }) })).toBe('21 photos added');
		expect(importHeadline({ status: 'done', progress: progress({ picked: 1, imported: 1 }) })).toBe('1 photo added');
		expect(importDetails({ status: 'done', progress: progress({ picked: 23, imported: 20, duplicates: 2, failed: 1 }) })).toEqual(['2 were already there', '1 couldn’t be copied']);
		expect(importHeadline({ status: 'failed', progress: progress({}), error_code: 'consent_denied' })).toBe('You didn’t allow access to Google Photos.');
		expect(importHeadline({ status: 'expired', progress: progress({}) })).toBe('Google Photos closed before anything was picked.');
	});
});
