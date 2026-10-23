import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Terms of use',
	updated: '2026-10-23',
	intro: [
		'These terms apply to the hosted kinframe service at https://kinframe.appmire.be: the website, the sign-in pages, and the kinframe apps and frames when they use this server. By creating an account or using the service, you agree to them. Please also read our privacy policy at https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'provider',
			title: 'Who provides the service',
			body: [
				'The service is provided by Appmire CommV, Langestraat 116, 1790 Affligem, Belgium, enterprise number BE 0535.825.723 (“Appmire”, “we”). Contact: jeroen@appmire.be.'
			]
		},
		{
			id: 'service',
			title: 'The service',
			body: [
				'kinframe lets you send photos, videos, captions and calendar events to photo frames of your family, and see when they arrive and how people react. The service is currently free of charge. We may add, change or remove features as the software develops.',
				'The kinframe software is free and open-source software under the GNU Affero General Public License (AGPL-3.0); its source code is at https://github.com/jeroentrappers/kinframe. These terms are about our hosted service, not the software: they do not limit the rights the AGPL gives you, and you are free to run your own kinframe server instead.'
			]
		},
		{
			id: 'account',
			title: 'Your account',
			body: [
				{
					list: [
						'You must be at least 13 years old to create an account.',
						'Use an e-mail address you control, and keep your sign-in (e-mail, passkeys and any linked Apple, Google, Microsoft, Facebook or GitHub account) secure. You are responsible for what is done with your account.',
						'One account is for one person. A frame does not need an account of its own.',
						'Frame owners decide who may join their frame and what members may do. Owners and admins are responsible for the people they invite.'
					]
				}
			]
		},
		{
			id: 'content',
			title: 'Your content',
			body: [
				'You keep all rights to the photos, videos, captions and other content you send. You give Appmire only the permission we need to run the service: to store your content, make copies in the right sizes for each frame, and show it to the frames and people you send or share it with. This permission ends when your content is deleted, except for copies kept in backups for a limited time.',
				'Only send content you have the right to share, that is legal, and that the people in it would agree to. Take extra care with photos of children and of people who cannot easily object.'
			]
		},
		{
			id: 'use',
			title: 'Acceptable use',
			body: [
				'You may not use the service to:',
				{
					list: [
						'store or send content that is illegal, including child sexual abuse material, content that infringes someone else’s copyright or privacy, or content that incites violence or hatred;',
						'harass, threaten or deceive other people;',
						'send spam, malware or anything meant to harm devices or people;',
						'disrupt or overload the service, get around its limits, or access accounts, frames or data that are not yours;',
						'run a commercial service for others on top of our hosted service, or store large amounts of data that have nothing to do with photo frames.'
					]
				}
			]
		},
		{
			id: 'report',
			title: 'Reporting illegal content',
			body: [
				'If you come across content on the service that you believe is illegal, report it to jeroen@appmire.be. Please say where you saw it and why you think it is illegal. We handle reports carefully and promptly, may remove or block access to the content, and will tell the person who sent it, unless the law or an investigation does not allow that. This address is also our point of contact for authorities and users under the EU Digital Services Act, in English, Dutch or French.'
			]
		},
		{
			id: 'suspension',
			title: 'Suspension and ending',
			body: [
				'If you break these terms or the law, or put the service or other people at risk, we may remove content, limit your access or suspend or close your account. Unless it is urgent or not allowed by law, we tell you first and explain why, and you can reply to jeroen@appmire.be if you disagree.',
				'You can stop using the service at any time and ask us to delete your account (see the privacy policy). If we decide to stop the service altogether, we will tell you at least 60 days in advance, so you have time to save your photos and move to another kinframe server.'
			]
		},
		{
			id: 'availability',
			title: 'Availability',
			body: [
				'We do our best to keep the service running and your content safe, but the service is provided free of charge and “as is”. We cannot promise that it will always be available or free of errors, or that content can never be lost. Keep your own copies of photos and videos that matter to you; kinframe is not a backup service.'
			]
		},
		{
			id: 'liability',
			title: 'Liability',
			body: [
				'To the extent Belgian law allows, Appmire is not liable for indirect damage, such as lost data, lost profit or damage caused by content other users send, or for interruptions of the service. This does not limit our liability for our intentional or grossly negligent acts, for death or personal injury, or any other liability that cannot be excluded by law. Your mandatory rights as a consumer are not affected.'
			]
		},
		{
			id: 'shop',
			title: 'Pre-order shop',
			body: [
				'Where the service offers frames for pre-order, a reservation is not a purchase: you pay nothing and are not bound to buy. The seller is named on the shop page and in your confirmation e-mail. When the frames are in stock, the seller e-mails you the final price, the delivery date and a payment link, and only if you then decide to buy does a sale take place, under the seller’s terms of sale and with your statutory consumer rights, including the right of withdrawal. You can cancel a reservation at any time with the link in your confirmation e-mail.'
			]
		},
		{
			id: 'changes',
			title: 'Changes to these terms',
			body: [
				'We may change these terms, for example when the service or the law changes. We announce important changes at least 30 days in advance by e-mail or in the app. If you do not agree with a change, you can stop using the service and have your account deleted before it takes effect.'
			]
		},
		{
			id: 'law',
			title: 'Applicable law and disputes',
			body: [
				'These terms are governed by Belgian law. Please contact us first at jeroen@appmire.be; we will try to find a solution together. If that does not work, the competent courts of Belgium decide, without affecting your right as a consumer to go to the courts of the place where you live and the protection of the mandatory law of your country.'
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
