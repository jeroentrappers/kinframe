import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Privacy policy',
	updated: '2026-10-27',
	intro: [
		'kinframe lets families send photos, videos and messages to a photo frame at home. This policy explains which personal data the hosted kinframe service at https://kinframe.appmire.be collects, why, who else is involved, how long it is kept and how you can have it deleted. It covers the website, the kinframe apps and frames when they use this server, the sign-in pages at auth.kinframe.appmire.be and the pre-order shop.',
		'In short: we only use your data to run the service. There are no ads, no analytics and no tracking, and nothing is sold or shared for marketing. The servers are in Germany.'
	],
	sections: [
		{
			id: 'controller',
			title: 'Who is responsible',
			body: [
				'The controller for your personal data is Appmire CommV, Langestraat 116, 1790 Affligem, Belgium, enterprise number BE 0535.825.723 (“Appmire”, “we”). For anything about your data, e-mail jeroen@appmire.be.',
				'kinframe is open-source software. Anyone can run their own kinframe server. If you use a server run by someone else, that person or organisation is responsible for your data there, not Appmire, and this policy does not apply to it.'
			]
		},
		{
			id: 'data',
			title: 'What we collect',
			body: [
				'We only collect what the service needs to work:',
				{
					list: [
						'Account: your e-mail address, the name you give at sign-up and a technical user ID. If you sign in with Apple, Google, Microsoft, Facebook or GitHub, we receive your name, your e-mail address (from Microsoft we do not use the e-mail address) and the provider’s ID for you, and depending on the provider a profile picture. If you use a passkey, we store its public key, never your fingerprint or face. We never receive passwords from these providers.',
						'Profile: your display name, an optional profile photo and your language.',
						'Families and frames: which frames you belong to and your role, invitations and join requests (with an optional note), frame names and settings, and, if the owner sets one, the frame’s location (a place name and its coordinates) for the weather and sunrise times.',
						'What you send: photos and videos, captions, albums, the time a photo was taken, the original file name, and when each item was delivered to and first shown on a frame. Location and camera data (EXIF/GPS) are removed from every photo and video we store; only the capture time is kept.',
						'Reactions and replies: hearts and quick replies sent from a frame.',
						'Calendars: calendar links (ICS or CalDAV) you add, with an optional user name and password, and the events fetched from them; events and invitations you send to a frame (title, place, notes, an optional photo, your name). Links and passwords are stored encrypted.',
						'Plugins (widgets on the frame): which plugins a frame uses and their settings, such as the address of a device in your home or a Home Assistant access token. Tokens and other secrets are stored encrypted and only sent to the frame. For the plugin settings in the apps, the frame also reports whether each widget works, its last error, the names and addresses of devices it found on your home network and, for Home Assistant, the names and units of your entities. We keep only the latest report. If your weather station uploads to kinframe, we keep its readings for 48 hours with its station ID; its key is only stored encrypted, in the frame’s settings.',
						'Frame device data: the frame’s brand and model, screen size, Android and app version, language, time zone, storage use, the name and signal strength of its Wi-Fi network, uptime and the number of recent crashes. We keep only the latest report. We do not collect serial numbers, MAC addresses, battery levels or the frame’s position.',
						'Notifications: if you turn them on, the address of your push subscription (provided by your browser or phone), a device label such as “Chrome on Android” and your language.',
						'Shop reservations: your name, e-mail address, country, an optional postal code and note, your language, the items you reserve and the time you gave consent. No payment data and no IP addresses.',
						'Technical data: our servers see your IP address when you connect. The kinframe server uses it in memory to limit abuse (rate limits) and does not store it with your data. The web server and the sign-in service keep logs (see “How long we keep data”).'
					]
				}
			]
		},
		{
			id: 'purposes',
			title: 'Why we use it, and on which legal basis',
			body: [
				{
					list: [
						'To provide the service you signed up for: your account, delivering what you send to the right frames, showing who sent it, reactions, calendars, weather and notifications (performance of a contract, GDPR article 6(1)(b)).',
						'To keep the service secure and working: sign-in security, preventing abuse, fixing faults and keeping frames up to date (our legitimate interest, article 6(1)(f)).',
						'To send you sign-in codes and, for the shop, your reservation confirmation and follow-up about it (contract or steps before a contract, and your consent in the reservation form).',
						'Push notifications only when you turn them on; you can turn them off at any time (consent, article 6(1)(a)).',
						'To meet legal obligations, for example when the authorities lawfully ask us for information (article 6(1)(c)).'
					]
				},
				'We do not use your data for advertising or profiling, and we do not make automated decisions about you. We do not look at your photos except when that is needed to handle a report of illegal content or a technical fault you report.'
			]
		},
		{
			id: 'sharing',
			title: 'Who can see your data',
			body: [
				'Within kinframe: the members of a frame see the display names and profile photos of the other members, and what is sent to that frame. Your e-mail address is not shown to other members. People you invite see your name with the invitation. Frame owners and admins also see the frame’s settings and device status. People who can see the frame in your home can of course see what it shows.',
				'Service providers and third parties we work with:',
				{
					list: [
						'Hetzner Online GmbH (Germany) hosts our server. The database and all photos and videos are stored on that server in Germany, the media in an object store we run ourselves.',
						'Open-Meteo (open-meteo.com) provides weather forecasts and place search. Our server sends it the coordinates of a frame’s location and the place names you search for, never who you are. Frames contact it themselves only for plugins that use it (see “Plugins” below).',
						'Push services: to deliver a notification, our server sends it to the push service of your browser or phone (for example Google, Mozilla or Apple for web notifications, or the UnifiedPush distributor you chose on Android). Notifications contain short texts such as a frame name, the name of someone who asks to join, or a calendar event title, but never photos or captions. Web notifications are end-to-end encrypted; UnifiedPush messages are sent over HTTPS to your distributor, which can read them.',
						'Sign-in providers: only if you choose to sign in with Apple, Google, Microsoft, Facebook (Meta) or GitHub. That provider then learns that you use kinframe, under its own privacy policy.',
						'Calendar hosts: if you add a calendar link, our server fetches it from the address you gave (for example Google Calendar or iCloud) with the credentials you entered.',
						'Plugins: a widget the frame owner adds may only talk to what it lists before it is added. Widgets for devices in your home (HomeWizard, Home Assistant) stay on your home network: nothing they read leaves it. Others contact an internet service directly from the frame: the rain radar asks Buienradar (buienradar.nl) or Open-Meteo for the rain at the frame’s location rounded to about a kilometre, and community plugins contact the hosts they list. These services see the frame’s internet address and what the widget asks for, never who you are, and handle that under their own privacy policies. Community plugins are written by other people and published in a public registry; we review them before publication and can withdraw them.',
						'Google Cast: only if you choose to show a slideshow on a Chromecast, your browser loads Google’s Cast software and the Chromecast loads the photos from our server.',
						'E-mail: sign-in codes and shop e-mails are sent through Appmire’s own mail server. They contain no tracking pixels.',
						'GitHub: our server downloads kinframe app updates and the community plugin registry from GitHub Releases. No personal data is sent; frames download updates from our server only.',
						'Shop: reservations are handled by the seller named on the shop page and in the confirmation e-mail, who uses them only for that reservation.'
					]
				},
				'We do not sell your data and do not share it with anyone else, unless the law requires it.'
			]
		},
		{
			id: 'transfers',
			title: 'Transfers outside the EU',
			body: [
				'We store and process your data in the European Union. Some providers you choose to use, such as a sign-in provider, a push service or your calendar host, may process data outside the EU under their own terms; for providers in the United States this is usually covered by the EU-US Data Privacy Framework or standard contractual clauses.'
			]
		},
		{
			id: 'retention',
			title: 'How long we keep data',
			body: [
				{
					list: [
						'Your account and profile: as long as you have an account.',
						'Photos and videos: as long as they are on a frame or in its library. Deleted items go to the frame’s trash and are removed for good after 30 days, or sooner if someone deletes them permanently. Automatic clean-up of the stored files themselves is not built yet: until it is, a removed photo is no longer shown or offered to anyone, but its stored file is only erased when we clean up or when you ask us (see “Deleting your data”).',
						'Uploads: the file you upload is deleted as soon as it has been processed; unfinished uploads after 24 hours.',
						'Frame device data: only the latest report is kept; each new report replaces the previous one.',
						'Push subscriptions: until you turn notifications off, sign out of the app, or the push service reports that the subscription has expired.',
						'Plugins: their settings until the owner removes the widget or the frame; the frame’s plugin report: only the latest.',
						'Calendars: until you remove the calendar or the frame.',
						'Shop reservations: deleted automatically one year after the last change, or 30 days after you cancel.',
						'Logs: the kinframe server does not log requests or IP addresses in normal operation. Our web server keeps access logs (IP address, time, address requested, browser) for at most 14 days, for security. The sign-in service keeps a record of sign-ins (time, IP address and browser) to protect your account.'
					]
				}
			]
		},
		{
			id: 'delete',
			title: 'Deleting your data',
			body: [
				'You can delete data yourself in the app or on the website:',
				{
					list: [
						'A photo or video: delete it from the frame’s library (if you have permission to manage it), or unsend something you sent from your history. It disappears from the frame and goes to the trash for 30 days.',
						'Leaving a frame: you can leave a frame and choose to remove everything you sent to it. Owners and admins can remove a member and their items in the same way.',
						'A frame: the owner can remove a frame. It is disconnected, everyone loses access and its photos are no longer shown.',
						'Notifications: turn them off in Settings; the push subscription is deleted.'
					]
				},
				'Deleting your account: the apps do not have a button for this yet. Send an e-mail to jeroen@appmire.be from the address you sign in with, with the subject “Delete my kinframe account”, and say whether the photos you sent should stay on the frames or be removed. We will delete your account, your sign-in identity (including any link to Apple, Google, Microsoft, Facebook or GitHub), your profile photo and your push subscriptions, and remove your photos if you ask. If you own a frame, tell us whether to remove it or to hand it over to another member. We do this within one month and confirm by e-mail. Items you choose to leave on a frame keep showing your name.',
				'Erasing files completely: if you want the stored files of photos you sent, or all data of a removed frame, erased from our servers (and not only removed from view), ask us at the same address and we will do it within one month.',
				'If you signed in with Facebook, you can also remove kinframe from your Facebook account under Settings & privacy → Settings → Apps and websites. That stops Facebook from sharing data with us; to delete the data we already have, send us the e-mail above. The same applies to Apple, Google, Microsoft and GitHub in their account settings.',
				'Our backups are overwritten in the normal backup cycle, so deleted data disappears from them over time.'
			]
		},
		{
			id: 'rights',
			title: 'Your rights',
			body: [
				'Under the GDPR you have the right to access your data and get a copy of it, to have it corrected or erased, to restrict or object to its use, to data portability, and to withdraw consent at any time (this does not affect what was done before). To use these rights, e-mail jeroen@appmire.be. We may ask you to confirm that the request comes from you, and we answer within one month.',
				'You can also file a complaint with the Belgian Data Protection Authority (Gegevensbeschermingsautoriteit / Autorité de protection des données), Drukpersstraat 35 / Rue de la Presse 35, 1000 Brussels, contact@apd-gba.be, https://www.dataprotectionauthority.be. We would appreciate the chance to sort it out with you first.'
			]
		},
		{
			id: 'storage',
			title: 'Cookies and storage on your device',
			body: [
				'The kinframe website sets no cookies of its own and uses no analytics or tracking. It stores a few things in your browser that it needs to work: your language, your sign-in session (managed by the sign-in software) and your notification setting. The sign-in pages at auth.kinframe.appmire.be use strictly necessary cookies for the sign-in session. All fonts and scripts are served by our own server; nothing is loaded from third parties, except Google’s Cast software when you start casting.',
				'The apps keep your sign-in securely on your device. The frame keeps a cache of its photos and videos so it works offline.'
			]
		},
		{
			id: 'security',
			title: 'Security',
			body: [
				'All connections use HTTPS. Frames only make outgoing connections and sign in with their own revocable key. Calendar links and passwords are encrypted in the database, the media store is not reachable from the internet, and photos are only served through signed, short-lived links. Access to the servers is limited to Appmire. If a data breach puts your rights at risk, we will tell you and the authority as the law requires.'
			]
		},
		{
			id: 'children',
			title: 'Children',
			body: [
				'You need to be at least 13 years old to create a kinframe account. Families often send photos of children: please only send photos of other people, and especially children, when you are allowed to and they or their parents would agree. A frame itself needs no account, so anyone can look at it.'
			]
		},
		{
			id: 'changes',
			title: 'Changes to this policy',
			body: [
				'If we change this policy, we update the date at the top. When a change matters for how we use your data, we tell you in advance by e-mail or in the app.'
			]
		},
		{
			id: 'contact',
			title: 'Contact',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, Belgium. Enterprise number BE 0535.825.723. E-mail: jeroen@appmire.be.'
			]
		}
	]
};

export default doc;
