import { expect, test } from '@playwright/test';
import { gradientPng, push } from './mock-api';
import { EMPTY_SUMMARY, sid } from './mock-social';
import { call, signIn, socialState } from './social-fixtures';

// Family social (docs/spec/family-social.md, README §5.18–§5.20): the web family app against the in-memory mock.

test('family social stays hidden until the server has it', async ({ page }) => {
	const { st } = socialState({ flags: false });
	await signIn(page, st, /\/frames$/);
	await expect(page.getByRole('link', { name: 'Home', exact: true })).toHaveCount(0);
	await expect(page.getByRole('link', { name: 'Chats' })).toHaveCount(0);
	await expect(page.getByRole('link', { name: 'Families' }).first()).toBeVisible();
	await page.goto('/home');
	await expect(page.getByText('This server doesn’t offer the family timeline yet.')).toBeVisible();
	await page.goto('/chats');
	await expect(page.getByText('This server doesn’t offer chats.')).toBeVisible();
	expect(call(st, 'GET', '/v1/timeline')).toBeUndefined();
});

test('Home: the strip, the timeline, reactions, prompts and On this day', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);

	// The strip: a quiet frame that asked for photos, and the family with its streak.
	const strip = page.getByRole('navigation', { name: 'Your frames and families' });
	await expect(strip.getByText('Would like new photos')).toBeVisible();
	await expect(strip.getByText('12 weeks')).toBeVisible();
	await strip.getByRole('button', { name: /Oma’s frame/ }).click();
	const suggest = page.getByRole('dialog', { name: 'Send something to Oma’s frame' });
	await expect(suggest.getByText(/asked for new photos/)).toBeVisible();
	await expect(suggest.getByRole('link', { name: 'Choose photos' })).toHaveAttribute('href', `/send?frame=${s.oma.id}`);
	await suggest.getByRole('button', { name: 'Close' }).click();

	// Cards: the milestone, this week's prompt and On this day.
	await expect(page.getByRole('heading', { name: 'You shared your first photo' })).toBeVisible();
	await page.getByRole('button', { name: 'Dismiss' }).first().click();
	await expect.poll(() => call(s.st, 'PATCH', /\/v1\/me\/milestones\/first_photo$/)?.body).toEqual({ seen: true });
	await expect(page.getByRole('heading', { name: 'Your view right now' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Answer with a photo' })).toHaveAttribute('href', `/send?feed=${s.feed.id}&prompt=${s.promptId}`);
	await expect(page.getByRole('heading', { name: 'On this day' })).toBeVisible();
	await expect(page.getByText('1 year ago in Peeters')).toBeVisible();

	// The timeline: frames and people in one reaction row, the newest comments, prompt answers grouped.
	const first = page.locator('article').filter({ hasText: 'Ben' }).first();
	await expect(first.getByText('Oma’s frame and Clara')).toBeVisible();
	await expect(first.getByText('So sweet')).toBeVisible();
	await expect(first.getByRole('link', { name: /View all 3 comments/ })).toBeVisible();
	await expect(first.getByText('Seen on 1 of 1 frame')).toBeVisible();
	await expect(page.getByRole('link', { name: '2 answers to “Your view right now”' })).toBeVisible();
	await expect(page.getByText('To Oma’s frame')).toBeVisible();

	await first.getByRole('button', { name: 'React', exact: true }).click();
	await page.getByRole('toolbar', { name: 'React' }).getByRole('button', { name: 'Love' }).click();
	await expect.poll(() => call(s.st, 'PUT', `/v1/reactions/post/${s.p1}`)?.body).toEqual({ emoji: 'love' });
	await expect(first.getByRole('button', { name: 'Your reaction: Love. Change it' })).toBeVisible();

	// Live: the thread changes on the server.
	push(s.st, { type: 'post.updated', post_id: s.p1, thread: { ...s.soc.posts[0]!.thread, comment_count: 7 } });
	await expect(first.getByRole('link', { name: /View all 7 comments/ })).toBeVisible();
});

test('a thread: mention, reply to a reply, edit, undo a delete, and live comments', async ({ page, isMobile }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/posts/${s.p1}`);

	await expect(page.getByText('Look at her face 😂')).toBeVisible();
	await expect(page.getByText('Haha, yes')).toBeVisible();
	await expect(page.getByText('Oma’s frame loved this')).toBeVisible();

	// A comment with a mention picked from the suggestions.
	const box = page.getByRole('combobox', { name: 'Add a comment…' });
	await box.click();
	await box.pressSequentially('Thanks @Be');
	await expect(page.getByRole('option', { name: 'Ben' })).toBeVisible();
	await box.press('Enter');
	await expect(box).toHaveValue('Thanks @Ben ');
	await box.pressSequentially('!');
	await page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect.poll(() => call(s.st, 'POST', `/v1/posts/${s.p1}/comments`)?.body).toEqual({ body: 'Thanks @Ben !', mentions: [{ user_id: s.ben.user_id, start: 7, length: 4 }] });
	await expect(page.getByRole('link', { name: '@Ben' }).last()).toBeVisible();

	// Replying to a reply hangs under the top comment and quotes the person.
	await page.locator(`#c-${s.c2.id}`).getByRole('button', { name: 'Reply' }).click();
	await expect(page.getByText('Replying to Ben')).toBeVisible();
	await page.getByRole('combobox', { name: 'Write a reply…' }).fill('Same here');
	await page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect
		.poll(() => s.st.calls.filter((c) => c.method === 'POST' && c.path === `/v1/posts/${s.p1}/comments`)[1]?.body)
		.toEqual({ body: 'Same here', parent_id: s.c1.id, quoted_user_id: s.ben.user_id });

	// Edit my own comment.
	await page.locator(`#c-${s.mine.id}`).getByRole('button', { name: 'Comment options' }).click();
	await page.getByRole('menuitem', { name: 'Edit' }).click();
	const edit = page.getByRole('combobox', { name: 'Edit comment' });
	await edit.fill('So very sweet');
	await page.locator(`#c-${s.mine.id}`).getByRole('button', { name: 'Save' }).click();
	await expect.poll(() => call(s.st, 'PATCH', `/v1/posts/${s.p1}/comments/${s.mine.id}`)?.body).toMatchObject({ body: 'So very sweet' });
	await expect(page.locator(`#c-${s.mine.id}`).getByText('edited')).toBeVisible();

	// Delete it, then undo.
	await page.locator(`#c-${s.mine.id}`).getByRole('button', { name: 'Comment options' }).click();
	await page.getByRole('menuitem', { name: 'Delete comment' }).click();
	await expect(page.locator(`#c-${s.mine.id}`)).toHaveCount(0);
	await page.getByRole('button', { name: 'Undo' }).click();
	await expect(page.locator(`#c-${s.mine.id}`)).toBeVisible();
	expect(call(s.st, 'DELETE', `/v1/posts/${s.p1}/comments/${s.mine.id}`)).toBeUndefined();

	// Someone else comments while we look.
	push(s.st, { type: 'comment.created', post_id: s.p1, comment: { id: sid('c'), post_id: s.p1, kind: 'person', author: s.clara, body: 'Live from Clara', created_at: new Date().toISOString(), reactions: EMPTY_SUMMARY } });
	await expect(page.getByText('Live from Clara')).toBeVisible();
	await expect.poll(() => call(s.st, 'PUT', `/v1/posts/${s.p1}/read`)).toBeTruthy();
	void isMobile;
});

test('moderators turn comments off and remove someone’s comment', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/posts/${s.p1}`);
	await page.getByRole('button', { name: 'Post options' }).click();
	await page.getByRole('menuitem', { name: 'Turn off comments' }).click();
	await expect.poll(() => call(s.st, 'PUT', `/v1/posts/${s.p1}/thread`)?.body).toEqual({ state: 'closed' });
	await expect(page.getByText('Comments are turned off').first()).toBeVisible();
	await expect(page.getByRole('combobox', { name: 'Add a comment…' })).toHaveCount(0);

	await page.locator(`#c-${s.c1.id}`).getByRole('button', { name: 'Comment options' }).first().click();
	await page.getByRole('menuitem', { name: 'Remove comment' }).click();
	// A moderator's removal takes the replies with it.
	await expect(page.getByText('Haha, yes')).toHaveCount(0);
	await expect.poll(() => call(s.st, 'DELETE', `/v1/posts/${s.p1}/comments/${s.c1.id}`), { timeout: 12_000 }).toBeTruthy();
});

test('chats: the list, sending, live messages, typing and “Seen”', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto('/chats');
	const fam = page.getByRole('link', { name: /Peeters/ });
	await expect(fam.getByLabel('2 unread messages')).toBeVisible();
	await expect(fam.getByText('The family’s October 2026')).toBeVisible();
	await expect(page.getByText(/could read them/)).toBeVisible();

	await page.getByRole('link', { name: /Ben/ }).click();
	await expect(page).toHaveURL(new RegExp(`/chats/${s.direct}$`));
	await expect(page.getByText('Hi Anna! Look at this')).toBeVisible();
	await expect(page.getByText('Seen', { exact: true })).toHaveCount(0);

	const box = page.getByRole('combobox', { name: 'Message' });
	await box.fill('See you Sunday');
	await page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect.poll(() => call(s.st, 'POST', `/v1/conversations/${s.direct}/messages`)?.body).toMatchObject({ body: 'See you Sunday' });
	await expect(page.getByText('See you Sunday')).toBeVisible();

	// Ben reads it, types, and answers.
	const sent = s.soc.messages[s.direct]!.at(-1)!;
	push(s.st, { type: 'read.updated', conversation_id: s.direct, user_id: s.ben.user_id, last_read_message_id: sent.id });
	await expect(page.getByText('Seen', { exact: true })).toBeVisible();
	push(s.st, { type: 'typing', conversation_id: s.direct, user_id: s.ben.user_id, display_name: 'Ben', activity: 'typing', expires_at: new Date(Date.now() + 6000).toISOString() });
	await expect(page.getByText('Ben is typing…')).toBeVisible();
	push(s.st, {
		type: 'message.created',
		conversation_id: s.direct,
		message: { id: sid('m'), conversation_id: s.direct, kind: 'person', author: s.ben, body: 'Great, see you!', items: [], reactions: EMPTY_SUMMARY, deleted: false, created_at: new Date().toISOString() }
	});
	await expect(page.getByText('Great, see you!')).toBeVisible();
	await expect(page.getByText('Ben is typing…')).toHaveCount(0);
	await expect.poll(() => call(s.st, 'PUT', `/v1/conversations/${s.direct}/read`)).toBeTruthy();
});

test('chat messages: reply, react, put photos on a frame', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/chats/${s.direct}`);

	const msg = page.locator(`#m-${s.benMsg.id}`);
	await msg.hover();
	await msg.getByRole('button', { name: 'Message options' }).click();
	await page.getByRole('menuitem', { name: 'Reply' }).click();
	await expect(page.getByText('Replying to Ben')).toBeVisible();
	await page.getByRole('combobox', { name: 'Message' }).fill('Wow');
	await page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect.poll(() => call(s.st, 'POST', `/v1/conversations/${s.direct}/messages`)?.body).toMatchObject({ body: 'Wow', reply_to_id: s.benMsg.id });

	await msg.hover();
	await msg.getByRole('button', { name: 'React' }).click();
	await page.getByRole('toolbar', { name: 'React' }).getByRole('button', { name: 'Thumbs up' }).click();
	await expect.poll(() => call(s.st, 'PUT', `/v1/reactions/message/${s.benMsg.id}`)?.body).toEqual({ emoji: 'thumbs_up' });

	await page.locator(`#m-${s.benPhoto.id}`).getByRole('button', { name: 'Put on a frame' }).click();
	const dlg = page.getByRole('dialog', { name: 'Put on a frame' });
	await expect(dlg.getByText(/These are Ben’s photos/)).toBeVisible();
	// Someone else's photos go to frames only.
	await expect(dlg.getByText('Peeters')).toHaveCount(0);
	await dlg.locator('label', { hasText: 'Oma’s frame' }).click();
	await dlg.getByRole('button', { name: 'Send' }).click();
	await expect.poll(() => call(s.st, 'POST', `/v1/conversations/${s.direct}/messages/${s.benPhoto.id}/send`)?.body).toEqual({ frame_ids: [s.oma.id] });
});

test('a new group chat with contacts', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto('/chats');
	await page.getByRole('button', { name: 'New chat' }).first().click();
	const dlg = page.getByRole('dialog', { name: 'New chat' });
	await dlg.locator('label', { hasText: 'Ben' }).click();
	await dlg.locator('label', { hasText: 'Clara' }).click();
	await dlg.getByLabel('Group name').fill('Cousins weekend');
	await dlg.getByRole('button', { name: 'Create group' }).click();
	await expect(page).toHaveURL(/\/chats\/.+/);
	expect(call(s.st, 'POST', '/v1/conversations')?.body).toEqual({ kind: 'group', member_ids: [s.ben.user_id, s.clara.user_id], name: 'Cousins weekend' });
	await expect(page.getByRole('heading', { name: 'Cousins weekend' })).toBeVisible();
});

test('settings: reminders, read receipts, blocked people and the reminder buttons', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto('/settings');
	await page.getByRole('radiogroup', { name: 'How often' }).getByText('Fewer').click();
	await expect.poll(() => call(s.st, 'PATCH', '/v1/me/reminders')?.body).toEqual({ level: 'fewer' });
	await page.locator('label.switch', { hasText: 'Read receipts' }).click();
	await expect.poll(() => call(s.st, 'PATCH', '/v1/me')?.body).toEqual({ show_read_receipts: false });
	await page.getByRole('button', { name: 'Unblock' }).click();
	await expect.poll(() => call(s.st, 'DELETE', /\/v1\/blocks\/.+/)).toBeTruthy();
	await expect(page.getByText('You haven’t blocked anyone.')).toBeVisible();

	// "Snooze 1 month" on a reminder opens the app at Settings when no tab was open.
	await page.goto('/settings?push_action=snooze_month');
	await expect.poll(() => s.st.calls.filter((c) => c.method === 'PATCH' && c.path === '/v1/me/reminders').map((c) => c.body)).toContainEqual({ snooze_days: 30 });
	await expect(page.getByText('Reminders snoozed for a month')).toBeVisible();
	await expect(page).toHaveURL(/\/settings$/);
});

test('a family: streak, chat, circles, readers, settings and reports', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/feeds/${s.feed.id}`);
	await expect(page.getByText('12 weeks')).toBeVisible();
	await expect(page.getByRole('link', { name: 'Family chat' })).toHaveAttribute('href', `/chats/${s.famConv}`);
	// The posts tab is the family's timeline.
	await expect(page.getByText('Look at her face 😂').or(page.getByText('So sweet')).first()).toBeVisible();

	await page.getByRole('tab', { name: 'Circles' }).click();
	await page.getByRole('button', { name: 'New circle' }).click();
	const dlg = page.getByRole('dialog', { name: 'New circle' });
	await dlg.getByLabel('Name').fill('The cousins');
	await dlg.locator('label', { hasText: 'Ben' }).click();
	await dlg.getByRole('button', { name: 'Create circle' }).click();
	await expect.poll(() => call(s.st, 'POST', `/v1/feeds/${s.feed.id}/circles`)?.body).toEqual({ name: 'The cousins', member_ids: [s.ben.user_id] });
	await expect(page.getByRole('heading', { name: 'The cousins' })).toBeVisible();

	await page.getByRole('tab', { name: 'Members' }).click();
	await expect(page.getByText('Reader')).toBeVisible();

	await page.getByRole('tab', { name: 'Settings' }).click();
	await page.locator('label.switch', { hasText: 'Everyone sees where posts were seen' }).click();
	await page.getByRole('button', { name: 'Save' }).first().click();
	await expect.poll(() => call(s.st, 'PATCH', `/v1/feeds/${s.feed.id}`)?.body).toEqual({ members_see_receipts: true });
	await expect(page.getByRole('heading', { name: 'Encouragement' })).toBeVisible();
	await page.locator('label.switch', { hasText: 'Weekly theme' }).click();
	await expect.poll(() => call(s.st, 'PATCH', `/v1/feeds/${s.feed.id}/engagement`)?.body).toEqual({ prompts_enabled: true });

	await page.getByRole('tab', { name: 'Reports' }).click();
	await expect(page.getByText('Unkind or hurtful')).toBeVisible();
	await page.getByRole('button', { name: 'Remove it' }).click();
	await expect.poll(() => call(s.st, 'POST', /\/v1\/reports\/.+\/resolve$/)?.body).toEqual({ action: 'remove' });
});

test('sending: readers can’t post, a circle as the audience, and a prompt answer', async ({ page }) => {
	const s = socialState();
	s.soc.circles[s.feed.id] = [
		{ id: sid('ci'), feed_id: s.feed.id, name: 'The cousins', my_role: 'admin', members: [{ user_id: s.me.user_id, display_name: 'Anna', role: 'admin' }], member_count: 1, frame_ids: [], frames_reached: [], conversation_id: sid('cv') }
	];
	await signIn(page, s.st);
	await page.goto(`/send?feed=${s.feed.id}&prompt=${s.promptId}`);
	await expect(page.getByText('Answers “Your view right now”')).toBeVisible();
	await expect(page.getByRole('checkbox', { name: /Peeters/ })).toBeChecked();
	await expect(page.getByRole('checkbox', { name: /Janssens/ })).toHaveCount(0);
	await page.getByTestId('audience').selectOption({ label: 'The cousins' });
	await page.getByTestId('file-input').setInputFiles([{ name: 'view.png', mimeType: 'image/png', buffer: await gradientPng(320, 240, 30) }]);
	await page.getByRole('button', { name: /^Send/ }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	expect(call(s.st, 'POST', '/v1/sends')?.body).toMatchObject({ feed_ids: [s.feed.id], circle_ids: [s.soc.circles[s.feed.id]![0]!.id], prompt_id: s.promptId });
});

test('the badge shows in the tab title and on Chats', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await expect.poll(() => page.title()).toMatch(/^\(1\) /);
	push(s.st, { type: 'badge.updated', badge: { total: 4, conversations: 3, threads: 1 } });
	await expect.poll(() => page.title()).toMatch(/^\(4\) /);
	push(s.st, { type: 'badge.updated', badge: { total: 0, conversations: 0, threads: 0 } });
	await expect.poll(() => page.title()).not.toMatch(/^\(/);
});

test('a person’s profile: start a chat and block', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/people/${s.clara.user_id}`);
	await expect(page.getByRole('heading', { name: 'Clara' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Peeters' })).toBeVisible();
	await page.getByRole('button', { name: 'Block' }).click();
	await expect.poll(() => call(s.st, 'PUT', `/v1/blocks/${s.clara.user_id}`)).toBeTruthy();
	await page.getByRole('button', { name: 'Unblock' }).click();
	await page.getByRole('button', { name: 'Message' }).click();
	await expect(page).toHaveURL(/\/chats\/.+/);
	expect(call(s.st, 'POST', '/v1/conversations')?.body).toEqual({ kind: 'direct', member_ids: [s.clara.user_id] });
});
