import { describe, expect, it } from 'vitest';
import type { EmailAddress, EmailMessage } from './api/client';
import {
	addressParts,
	addressPreview,
	addressView,
	authText,
	cleanSlugInput,
	heldWarning,
	isValidSlug,
	looksLikeEmail,
	mailtoHref,
	outcomeText,
	pendingName,
	senderLine,
	shareMessage,
	skipText,
	slugify
} from './emailInbox';
import { joinVia } from './joinRequests';

const now = new Date('2026-11-19T12:00:00Z');

function address(p: Partial<EmailAddress> = {}): EmailAddress {
	return {
		id: 'a1',
		address: 'oma-k7q2m9@in.kinframe.appmire.be',
		slug: 'oma',
		name: 'Oma’s frame',
		target: { kind: 'frame', frame_id: 'f1', frame_name: 'Oma’s frame' },
		owner: { user_id: 'u1', display_name: 'Anna' },
		status: 'active',
		anyone_may_send: false,
		send_confirmations: true,
		offer_app_invite: true,
		notify_reactions: true,
		counts: { allowed_senders: 1, pending_senders: 0, held_messages: 0 },
		created_at: '2026-11-01T10:00:00Z',
		...p
	};
}

describe('slugs', () => {
	it('suggests a friendly part from the name, like the server', () => {
		expect(slugify('Oma')).toBe('oma');
		expect(slugify('Oma’s frame')).toBe('omas-frame');
		expect(slugify('Peeters kids')).toBe('peeters-kids');
		expect(slugify('Café Zoë & Jürgen!')).toBe('cafe-zoe-jurgen');
		expect(slugify('  --  ')).toBe('');
		expect(slugify('a'.repeat(40))).toHaveLength(24);
		expect(slugify('abcdefghijklmnopqrstuvw xyz')).toBe('abcdefghijklmnopqrstuvw');
	});
	it('cleans what is typed and validates it', () => {
		expect(cleanSlugInput('Oma ')).toBe('oma-');
		expect(cleanSlugInput('-oma--peeters')).toBe('oma-peeters');
		expect(isValidSlug('oma')).toBe(true);
		expect(isValidSlug('oma-')).toBe(false);
		expect(isValidSlug('Oma')).toBe(false);
		expect(isValidSlug('')).toBe(false);
		expect(isValidSlug('a'.repeat(25))).toBe(false);
	});
	it('previews the address before the random part exists', () => {
		expect(addressPreview('oma', 'in.kinframe.appmire.be')).toBe('oma-••••••@in.kinframe.appmire.be');
		expect(addressPreview('oma-', null)).toBe('oma-••••••');
	});
});

describe('the address', () => {
	it('splits into the friendly part, the random part and the domain', () => {
		expect(addressParts('oma-k7q2m9@in.kinframe.appmire.be', 'oma')).toEqual({ slug: 'oma', random: 'k7q2m9', domain: 'in.kinframe.appmire.be' });
		expect(addressParts('weird@x.be', 'oma')).toEqual({ slug: 'weird', random: '', domain: 'x.be' });
	});
	it('makes the ready-made message for a relative', () => {
		expect(shareMessage(address())).toBe(
			'You can now email photos to Oma’s frame: oma-k7q2m9@in.kinframe.appmire.be. Just attach them; the subject becomes the caption. The first time, Anna approves your address.'
		);
		expect(shareMessage(address({ owner: null }), 'Ben')).toContain('The first time, Ben approves your address.');
		expect(shareMessage(address({ owner: null }))).toContain('the family approves your address');
	});
	it('builds a mailto link', () => {
		expect(mailtoHref('oma-k7q2m9@in.kinframe.appmire.be')).toBe('mailto:oma-k7q2m9@in.kinframe.appmire.be');
		expect(mailtoHref('oma-k7q2m9@in.kinframe.appmire.be', 'Oma’s frame')).toBe('mailto:oma-k7q2m9@in.kinframe.appmire.be?subject=Oma%E2%80%99s%20frame');
	});
	it('says how it is doing', () => {
		expect(addressView(address(), 'en', now)).toEqual({ tone: 'ok', label: 'Ready', pending: null });
		expect(addressView(address({ last_received_at: '2026-11-19T11:00:00Z' }), 'en', now).label).toBe('Ready · last email 1 hour ago');
		expect(addressView(address({ status: 'paused' }), 'en', now).label).toBe('Paused: emails to it bounce');
		const waiting = addressView(address({ counts: { allowed_senders: 1, pending_senders: 2, held_messages: 3 } }), 'en', now);
		expect(waiting.tone).toBe('attention');
		expect(waiting.pending).toBe('2 people are waiting for your OK');
	});
});

describe('senders and held mail', () => {
	it('names senders', () => {
		expect(senderLine({ email: 'jan.peeters@telenet.be', display_name: 'Jan' })).toBe('Jan · jan.peeters@telenet.be');
		expect(senderLine({ email: 'jan.peeters@telenet.be', display_name: ' ' })).toBe('jan.peeters@telenet.be');
		expect(pendingName({ email: 'rita@gmail.com', display_name: null })).toBe('rita@gmail.com');
		expect(looksLikeEmail('rita@gmail.com')).toBe(true);
		expect(looksLikeEmail('rita@gmail')).toBe(false);
	});
	it('warns about mail that could not be verified', () => {
		expect(heldWarning('unverified_sender', 'jan@example.org')).toBe('We couldn’t verify that this really comes from jan@example.org.');
		expect(heldWarning('unknown_sender', 'jan@example.org')).toBeNull();
		expect(authText({ authenticated: true, aligned: 'dkim', dkim_domain: 'gmail.com', from_domain: 'gmail.com' })).toBe('DKIM passed for gmail.com');
		expect(authText({ authenticated: false, from_domain: 'example.org' })).toBe('example.org doesn’t vouch for this email');
	});
});

describe('recent mail', () => {
	const m = (p: Partial<EmailMessage>): EmailMessage => ({ id: 'm', received_at: '2026-11-18T18:03:00Z', authenticated: true, outcome: 'delivered', sender_name: 'Jan', sender_email: 'jan@telenet.be', ...p });
	it('words every outcome as the spec does', () => {
		expect(outcomeText(m({ items: 4 }))).toBe('Jan · 4 photos delivered');
		expect(outcomeText(m({ items: 1 }))).toBe('Jan · 1 photo delivered');
		expect(outcomeText(m({ outcome: 'held', sender_name: 'Rita' }))).toBe('Rita · waiting for your OK');
		expect(outcomeText(m({ outcome: 'no_media' }))).toBe('Jan · no photos found');
		expect(outcomeText(m({ outcome: 'rejected_dmarc', sender_email: 'x@example.org' }))).toBe('Not really from example.org · refused');
		expect(outcomeText(m({ outcome: 'ignored' }))).toBe('Automatic message · ignored');
		expect(outcomeText(m({ outcome: 'blocked' }))).toBe('Blocked sender · dropped');
		expect(outcomeText(m({ outcome: 'duplicate', sender_name: null }))).toBe('jan@telenet.be · already received');
	});
	it('lists skipped files', () => {
		expect(skipText({ too_small: 2, duplicate: 1, too_long: 1 })).toBe('2 too small, 1 already there, 1 video too long');
		expect(skipText({})).toBe('');
		expect(skipText(null)).toBe('');
	});
});

describe('join requests from outside channels', () => {
	it('says where the request came from', () => {
		expect(joinVia({ via: 'email', invited_by_name: 'Anna' }, 'code')).toBe('Asked to join from the email link');
		expect(joinVia({ via: 'telegram' }, 'code')).toBe('Asked to join from Telegram');
		expect(joinVia({ via: 'invite', invited_by_name: 'Anna' }, 'code')).toBe('Invited by Anna');
		expect(joinVia({ via: 'frame_code' }, 'Used the frame code')).toBe('Used the frame code');
	});
});
