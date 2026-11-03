// The Plugins section of the frame settings (docs/plugins.md §6.1), against the real manifests in plugins/.
import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { communityPlugin, defaultSettings, emptyState, makeFrame, mockApi, pluginUpdate, type MockState } from './mock-api';

type S = components['schemas'];
const manifest = (dir: string) => JSON.parse(readFileSync(new URL(`../../plugins/${dir}/plugin.json`, import.meta.url), 'utf8'));
const SHOTS = process.env.SCREENSHOT_DIR;

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

async function shot(page: Page, name: string) {
	if (!SHOTS) return;
	await page.waitForTimeout(300);
	await page.screenshot({ path: `${SHOTS}/${test.info().project.name}-${name}.png`, fullPage: true });
}

function setup(catalogAt: string | null = new Date().toISOString()) {
	const state = emptyState();
	const frame = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	state.frames = [frame];
	const catalog: S['CatalogPlugin'][] = [
		{ manifest: manifest('kinframe.ha-energy'), locales: { en: { '@option.grid_sign.import': 'Taking from the grid' } } },
		{ manifest: manifest('kinframe.ha-ev') },
		{ manifest: manifest('kinframe.homewizard-p1') },
		{ manifest: manifest('examples/community.air-quality') }
	];
	state.plugins[frame.id] = { catalog, catalog_at: catalogAt, services: { '_hwenergy._tcp': [{ name: 'P1 meter', url: 'http://192.168.1.20' }] } };
	return { state, frame };
}

const patches = (state: MockState) => state.calls.filter((c) => c.method === 'PATCH' && c.path.endsWith('/settings'));

test('add a plugin, save it incomplete, pick entities the frame found, and switch it on', async ({ page }) => {
	const { state, frame } = setup();
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);

	const section = page.getByRole('region', { name: 'Plugins' });
	await expect(section.getByText('No plugins yet.')).toBeVisible();
	await shot(page, 'plugins-01-empty');

	// The catalog shows what each plugin may reach.
	await section.getByRole('button', { name: 'Add plugin' }).click();
	const add = page.getByRole('dialog', { name: 'Add plugin' });
	await expect(add.getByText('Reaches the internet: air-quality-api.open-meteo.com')).toBeVisible();
	await expect(add.getByText('Uses the frame’s place')).toBeVisible();
	await shot(page, 'plugins-02-catalog');
	await add.getByRole('button', { name: 'Add Home Assistant energy flow' }).click();

	const editor = page.getByRole('dialog', { name: 'Home Assistant energy flow' });
	await expect(editor.getByText('Save the address and token')).toBeVisible();
	await expect(editor.getByRole('radio', { name: 'Taking from the grid' })).toBeChecked();
	await editor.getByLabel('Home Assistant address').fill('http://homeassistant.local:8123');
	await editor.getByLabel('Long-lived access token').fill('secret-token');
	await shot(page, 'plugins-03-editor-new');
	await editor.getByRole('button', { name: 'Save' }).click();

	// Incomplete (no grid sensor): saved switched off, the editor stays open and marks what is missing.
	await expect(editor.getByText('Saved. It shows on the frame once the marked fields are filled in.')).toBeVisible();
	await expect(editor.getByText('Fill this in.')).toBeVisible();
	const first = patches(state)[0]!.body as { plugins: { instances: S['PluginInstance'][] } };
	expect(first.plugins.instances).toHaveLength(1);
	const sent = first.plugins.instances[0]!;
	expect(sent.instance_id).toMatch(/^p-[a-z0-9]{10}$/);
	expect(sent).toMatchObject({
		plugin_id: 'kinframe.ha-energy',
		slot: 'top-right',
		enabled: false,
		settings: { base_url: 'http://homeassistant.local:8123' },
		secrets: { token: 'secret-token' }
	});
	await expect(editor.getByText('Set', { exact: true })).toBeVisible();

	// The frame reports what it found; the open editor polls for it.
	state.plugins[frame.id]!.instances = [
		{
			instance_id: sent.instance_id,
			running: false,
			entities: [
				{ entity_id: 'sensor.grid_power', name: 'Grid power', unit: 'W' },
				{ entity_id: 'sensor.solar_power', name: 'Solar power', unit: 'W' },
				{ entity_id: 'sensor.grid_kw', name: 'Grid kW', unit: 'kW' }
			]
		}
	];
	await expect(editor.getByText('Save the address and token')).toBeHidden({ timeout: 8000 });
	const grid = editor.getByRole('combobox', { name: /Grid power/ });
	await grid.fill('grid');
	const options = page.getByRole('listbox', { name: 'Entities' }).getByRole('option');
	await expect(options).toHaveCount(1);
	await shot(page, 'plugins-04-entities');
	await options.first().click();
	await expect(grid).toHaveValue('sensor.grid_power');
	await editor.getByRole('button', { name: 'Save' }).click();
	await expect(editor).toBeHidden();

	const second = patches(state)[1]!.body as { plugins: { instances: S['PluginInstance'][] } };
	expect(second.plugins.instances[0]).toMatchObject({ enabled: true, settings: { base_url: 'http://homeassistant.local:8123', grid: 'sensor.grid_power' } });
	expect(second.plugins.instances[0]).not.toHaveProperty('secrets');
	expect(second.plugins.instances[0]).not.toHaveProperty('secrets_set');
	expect(patches(state)[1]!.headers?.['if-match']).toBe('"2"');

	await expect(section.getByText('Waiting for the frame')).toBeVisible();
	await expect(section.getByRole('checkbox', { name: 'Show Home Assistant energy flow on the frame' })).toBeChecked();
	await shot(page, 'plugins-05-list');

	// The layout goes with the save bar like every other setting.
	await section.getByText('Beside the photo').click();
	await page.getByRole('button', { name: 'Save', exact: true }).click();
	await expect.poll(() => patches(state).length).toBe(3);
	expect(patches(state)[2]!.body).toEqual({ plugins: { layout: { mode: 'band' } } });
});

test('presets ask for their placeholder first; remove asks first', async ({ page }) => {
	const { state, frame } = setup();
	state.settings[frame.id] = {
		...defaultSettings(),
		plugins: {
			layout: { mode: 'overlay' },
			instances: [
				{ instance_id: 'p-car0000001', plugin_id: 'kinframe.ha-ev', slot: 'top-left', enabled: true, settings: { base_url: 'http://ha:8123' } as unknown as S['PluginInstance']['settings'] },
				{ instance_id: 'p-p1meter001', plugin_id: 'kinframe.homewizard-p1', enabled: false, settings: {} }
			]
		}
	};
	state.pluginSecrets['p-car0000001'] = { token: 'x' };
	(state.settings[frame.id]!.plugins!.instances![0] as { secrets_set?: string[] }).secrets_set = ['token'];
	state.plugins[frame.id]!.instances = [{ instance_id: 'p-car0000001', running: true, error: 'Home Assistant refused the token' }];
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);

	const section = page.getByRole('region', { name: 'Plugins' });
	await expect(section.getByText('Home Assistant refused the token')).toBeVisible();
	await expect(section.getByText('Needs settings')).toBeVisible();

	// Switching on an incomplete instance opens its form instead.
	await section.getByRole('checkbox', { name: 'Show HomeWizard P1 meter on the frame' }).click();
	const p1 = page.getByRole('dialog', { name: 'HomeWizard P1 meter' });
	await expect(p1).toBeVisible();
	await p1.getByRole('button', { name: /P1 meter/ }).click();
	await expect(p1.getByLabel('Meter address')).toHaveValue('http://192.168.1.20');
	await p1.getByRole('button', { name: 'Cancel' }).click();
	expect(patches(state)).toHaveLength(0);

	await section.getByRole('button', { name: 'Edit Car battery (Home Assistant)' }).click();
	const car = page.getByRole('dialog', { name: 'Car battery (Home Assistant)' });
	await car.getByLabel('Fill in for').selectOption('kia_hyundai');
	await car.getByRole('button', { name: 'Fill in', exact: true }).click();
	await expect(car.getByText('Fill this in.')).toBeVisible();
	await car.locator('input[id$="-pi-prefix"]').fill('ev3');
	await car.getByRole('button', { name: 'Fill in', exact: true }).click();
	await expect(car.getByRole('combobox', { name: 'Battery level' })).toHaveValue('sensor.ev3_ev_battery_level');
	await shot(page, 'plugins-06-preset');
	await car.getByRole('button', { name: 'Save' }).click();
	await expect(car).toBeHidden();
	const saved = (patches(state)[0]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances;
	expect(saved.map((x) => x.instance_id)).toEqual(['p-car0000001', 'p-p1meter001']);
	expect(saved[0]!.settings).toMatchObject({ prefix: 'ev3', battery_level: 'sensor.ev3_ev_battery_level', charging: 'binary_sensor.ev3_ev_battery_charge' });

	await section.getByRole('button', { name: 'Edit Car battery (Home Assistant)' }).click();
	await car.getByRole('button', { name: 'Remove plugin' }).click();
	const confirm = page.getByRole('dialog', { name: 'Remove Car battery (Home Assistant)?' });
	await confirm.getByRole('button', { name: 'Remove plugin' }).click();
	await expect(page.getByText('Car battery (Home Assistant) was removed')).toBeVisible();
	const after = (patches(state)[1]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances;
	expect(after.map((x) => x.instance_id)).toEqual(['p-p1meter001']);
});

test('time zone settings: search by city, show region and offset, keep an unknown id visible', async ({ page }) => {
	const { state, frame } = setup();
	const clock = {
		id: 'test.clock',
		name: 'Clocks',
		version: '1.0.0',
		settings: [
			{ key: 'city_1', type: 'timezone', label: 'First city', required: true },
			{ key: 'city_2', type: 'timezone', label: 'Second city' },
			{ key: 'city_3', type: 'timezone', label: 'Third city' }
		]
	};
	state.plugins[frame.id]!.catalog!.push({ manifest: clock as unknown as S['CatalogPlugin']['manifest'] });
	state.settings[frame.id] = {
		...defaultSettings(),
		plugins: {
			instances: [
				{
					instance_id: 'p-clock00001',
					plugin_id: 'test.clock',
					enabled: true,
					settings: { city_1: 'Asia/Tokyo', city_3: 'Mars/Olympus_Mons' } as unknown as S['PluginInstance']['settings']
				}
			]
		}
	};
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);
	const section = page.getByRole('region', { name: 'Plugins' });
	await section.getByRole('button', { name: 'Edit Clocks' }).click();
	const editor = page.getByRole('dialog', { name: 'Clocks' });

	const first = editor.getByRole('combobox', { name: 'First city' });
	await expect(first).toHaveValue('Tokyo');
	await expect(editor.getByText('Asia · UTC+9')).toBeVisible();
	// A stored id this browser doesn't know shows as written, marked.
	const third = editor.getByRole('combobox', { name: 'Third city' });
	await expect(third).toHaveValue('Mars/Olympus_Mons');
	await expect(editor.getByText('Pick a time zone from the list.')).toBeVisible();

	const second = editor.getByRole('combobox', { name: 'Second city' });
	await second.fill('new york');
	const options = page.getByRole('listbox', { name: 'Time zones' }).getByRole('option');
	await expect(options).toHaveCount(1);
	await expect(options.first()).toContainText('New York');
	await expect(options.first()).toContainText(/America · UTC\u2212[45]/);
	await shot(page, 'plugins-07-timezone');
	await options.first().click();
	await expect(second).toHaveValue('New York');

	// Search by region, pick with the keyboard; clearing a field clears the setting.
	await third.fill('');
	await expect(editor.getByText('Pick a time zone from the list.')).toBeHidden();
	await third.fill('pacific');
	await expect(page.getByRole('listbox', { name: 'Time zones' }).getByRole('option').first()).toBeVisible();
	await third.fill('auckland');
	await third.press('ArrowDown');
	await third.press('Enter');
	await expect(third).toHaveValue('Auckland');

	await editor.getByRole('button', { name: 'Save' }).click();
	await expect(editor).toBeHidden();
	const saved = (patches(state)[0]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances[0]!;
	expect(saved.settings).toEqual({ city_1: 'Asia/Tokyo', city_2: 'America/New_York', city_3: 'Pacific/Auckland' });
});

test('an older frame app asks to be updated', async ({ page }) => {
	const { state, frame } = setup(null);
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);
	const section = page.getByRole('region', { name: 'Plugins' });
	await expect(section.getByText('Update the frame app')).toBeVisible();
	await expect(section.getByRole('button', { name: 'Add plugin' })).toHaveCount(0);
});

test('community plugins: their own group, what they may do before adding, the version a new one gets', async ({ page }) => {
	const { state, frame } = setup();
	state.plugins[frame.id]!.catalog!.push(communityPlugin('1.1.0'));
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);
	const section = page.getByRole('region', { name: 'Plugins' });

	await section.getByRole('button', { name: 'Add plugin' }).click();
	const add = page.getByRole('dialog', { name: 'Add plugin' });
	await expect(add.getByRole('heading', { name: 'Built in' })).toBeVisible();
	const group = add.getByRole('region', { name: 'Community' });
	await expect(group.getByText('Bin day')).toBeVisible();
	await expect(group.getByText('by Jane Doe · MIT · version 1.1.0')).toBeVisible();
	await expect(group.getByText('Only reaches the addresses you fill in')).toBeVisible();
	await expect(add.getByRole('region', { name: 'Built in' }).getByText('Bin day')).toHaveCount(0);
	await shot(page, 'plugins-08-catalog-community');

	// Adding asks first, listing exactly what it may do; Cancel adds nothing.
	await group.getByRole('button', { name: 'Add Bin day' }).click();
	const confirm = page.getByRole('dialog', { name: 'Add Bin day?' });
	await expect(confirm.getByText('This plugin comes from the community, not from kinframe. What it may do:')).toBeVisible();
	await expect(confirm.getByText('Reaches the addresses you fill in: Calendar address')).toBeVisible();
	await expect(confirm.getByText('Shows buttons on the frame that change things')).toBeVisible();
	await expect(confirm.getByText('Reaches the internet')).toHaveCount(0);
	await expect(confirm.getByText('Uses the frame’s place')).toHaveCount(0);
	await shot(page, 'plugins-09-confirm');
	await confirm.getByRole('button', { name: 'Cancel' }).click();
	await expect(confirm).toBeHidden();
	await expect(page.getByRole('dialog', { name: 'Bin day' })).toHaveCount(0);

	await section.getByRole('button', { name: 'Add plugin' }).click();
	await add.getByRole('button', { name: 'Add Bin day' }).click();
	await confirm.getByRole('button', { name: 'Add', exact: true }).click();
	const editor = page.getByRole('dialog', { name: 'Bin day' });
	await expect(editor.getByText('Community', { exact: true })).toBeVisible();
	await editor.getByLabel('Calendar address').fill('https://cal.example.org/bins.ics');
	await editor.getByRole('button', { name: 'Save' }).click();
	await expect(editor).toBeHidden();
	const sent = (patches(state)[0]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances[0]!;
	expect(sent).toMatchObject({ plugin_id: 'community.bin-day', version: '1.1.0', enabled: true, settings: { calendar_url: 'https://cal.example.org/bins.ics' } });

	// Built-in plugins add straight away, as before.
	await section.getByRole('button', { name: 'Add plugin' }).click();
	await add.getByRole('button', { name: 'Add HomeWizard P1 meter' }).click();
	await expect(page.getByRole('dialog', { name: 'HomeWizard P1 meter' })).toBeVisible();
});

test('an update asking for other permissions waits for approval; a withdrawn plugin says so', async ({ page }) => {
	const { state, frame } = setup();
	state.plugins[frame.id]!.catalog!.push(communityPlugin('1.0.0'));
	state.settings[frame.id] = {
		...defaultSettings(),
		plugins: {
			layout: { mode: 'overlay' },
			instances: [
				{
					instance_id: 'p-bins000001',
					plugin_id: 'community.bin-day',
					version: '1.0.0',
					enabled: true,
					settings: { calendar_url: 'https://cal.example.org/bins.ics' } as unknown as S['PluginInstance']['settings']
				},
				{ instance_id: 'p-gone000001', plugin_id: 'community.gone', version: '0.3.0', enabled: true, settings: {} }
			]
		}
	};
	state.plugins[frame.id]!.instances = [
		pluginUpdate('p-bins000001', '1.0.0', '1.2.0', { http: ['settings.calendar_url', 'https://holidays.example.org'], location: true, actions: true }),
		{ instance_id: 'p-gone000001', running: false, error: 'withdrawn: sent the token to the wrong host' }
	];
	await mockApi(page, state);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);
	const section = page.getByRole('region', { name: 'Plugins' });
	await expect(section.getByText('Withdrawn from the community registry: sent the token to the wrong host')).toBeVisible();
	await expect(section.getByRole('button', { name: 'Remove community.gone?' })).toBeVisible();
	await expect(section.getByText('An update waits for your approval')).toBeVisible();
	await shot(page, 'plugins-10-list-update');

	// The editor names what the new version asks for beyond the one the frame runs.
	await section.getByRole('button', { name: 'Edit Bin day' }).click();
	const editor = page.getByRole('dialog', { name: 'Bin day' });
	const update = editor.getByRole('region', { name: 'An update waits for your approval' });
	await expect(update.getByText('Update to 1.2.0 available — it asks for:')).toBeVisible();
	await expect(update.getByText('Reaches the internet: holidays.example.org')).toBeVisible();
	await expect(update.getByText('Uses the frame’s place')).toBeVisible();
	await expect(update.getByText('Reaches the addresses you fill in')).toHaveCount(0);
	await expect(update.getByText('Shows buttons on the frame')).toHaveCount(0);
	await expect(editor.getByText('The frame runs version 1.0.0.')).toBeVisible();
	await expect(editor.getByText('by Jane Doe · MIT')).toBeVisible();
	await shot(page, 'plugins-11-update');

	// "Not now" hides it for now, in the list too; nothing is saved.
	await update.getByRole('button', { name: 'Not now' }).click();
	await expect(update).toBeHidden();
	await editor.getByRole('button', { name: 'Cancel' }).click();
	await expect(section.getByText('An update waits for your approval')).toBeHidden();
	expect(patches(state)).toHaveLength(0);

	// It isn't kept: after a reload it asks again. Someone else saved meanwhile (412): approving still works.
	await page.reload();
	await section.getByRole('button', { name: 'Edit Bin day' }).click();
	state.settings[frame.id]!.version += 1;
	await update.getByRole('button', { name: 'Approve' }).click();
	await expect(page.getByText('Update to 1.2.0 approved')).toBeVisible();
	await expect(update).toBeHidden();
	const sent = patches(state);
	expect(sent).toHaveLength(2);
	expect(sent[0]!.headers?.['if-match']).toBe('"1"');
	expect(sent[1]!.headers?.['if-match']).toBe('"2"');
	const instances = (sent[1]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances;
	expect(instances.map((x) => [x.instance_id, x.version])).toEqual([
		['p-bins000001', '1.2.0'],
		['p-gone000001', '0.3.0']
	]);
	expect(instances[0]).toMatchObject({ enabled: true, settings: { calendar_url: 'https://cal.example.org/bins.ics' } });
	expect(instances[0]).not.toHaveProperty('secrets_set');
	// The editor stays open for the settings; saving them keeps the approved version.
	await editor.getByRole('button', { name: 'Save' }).click();
	await expect(editor).toBeHidden();
	const last = (patches(state)[2]!.body as { plugins: { instances: S['PluginInstance'][] } }).plugins.instances[0]!;
	expect(last.version).toBe('1.2.0');
});
