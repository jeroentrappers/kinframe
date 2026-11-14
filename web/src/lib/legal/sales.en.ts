import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Terms of sale',
	updated: '2026-11-14',
	intro: [
		'These terms apply to every purchase in the Familijst webshop (https://familijst.be). Please read them before you order: when you order, you accept them. Our privacy policy is at https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'seller',
			title: 'Who sells',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, Belgium, company and VAT number BE 0535.825.723. E-mail: jeroen@appmire.be, phone: +32 497 05 33 10.'
			]
		},
		{
			id: 'products',
			title: 'Products and prices',
			body: [
				'We sell Wi-Fi photo frames with the kinframe app installed, ready to use. Each product page describes the frame and its main features.',
				'Prices are in euro and include 21% VAT. Shipping costs, if any, are shown before you pay. The kinframe service on the frame is free and has no subscription; its use is governed by the kinframe terms of use at https://kinframe.appmire.be/terms.'
			]
		},
		{
			id: 'order',
			title: 'Ordering and payment',
			body: [
				'You choose your frames, enter your details and delivery address, accept these terms and pay online. The contract is concluded once your payment succeeds; we then confirm your order by e-mail.',
				'Payments are handled by our payment provider Mollie B.V. (Bancontact, payment cards and other methods shown at checkout). We never see your card details.',
				'If a price on the site is obviously wrong, we may cancel the order; you then get your money back in full.'
			]
		},
		{
			id: 'delivery',
			title: 'Delivery',
			body: [
				'We deliver in Belgium only. The expected delivery time is shown in the shop and in your confirmation. We deliver at the latest 30 days after your order; if we can’t, you may cancel the order and we refund you right away.',
				'The risk of loss or damage passes to you when you, or someone you name, receive the parcel. Please let us know within a few days if the parcel arrives damaged.'
			]
		},
		{
			id: 'withdrawal',
			title: 'Right of withdrawal (14 days)',
			body: [
				'As a consumer you may withdraw from your purchase within 14 days after you received the frame, without giving a reason. To withdraw, use the link in your order e-mail, or tell us clearly by e-mail or letter (you may use the model form below).',
				'Send the frame back to us within 14 days after you withdrew, to the address above. You pay the direct cost of returning it. Handle the frame only as you would in a shop; you are liable for any loss in value caused by handling beyond what is needed to see how it works.',
				'We refund everything you paid, including the standard delivery cost, within 14 days after we received your withdrawal, with the same payment method. We may wait with the refund until we have the frame back or you show that you sent it.',
				'Model withdrawal form (fill in and send only if you want to withdraw):',
				{
					list: [
						'To Appmire CommV, Langestraat 116, 1790 Affligem, Belgium, jeroen@appmire.be:',
						'I/We hereby give notice that I/we withdraw from my/our contract for the sale of the following goods: …',
						'Ordered on … / received on …, order number …',
						'Name and address of the consumer(s): …',
						'Signature (only on paper) and date: …'
					]
				}
			]
		},
		{
			id: 'warranty',
			title: 'Warranty',
			body: [
				'Every frame comes with the legal warranty of conformity of two years from delivery. If the frame is defective, contact us as soon as possible and at the latest two months after you noticed the defect. We repair or replace it, or, if that is not possible, refund you. Damage caused by misuse, falls or water is not covered.'
			]
		},
		{
			id: 'liability',
			title: 'Liability',
			body: [
				'We are liable for damage caused by our fault as the law provides. We are not liable for indirect damage, such as loss of data, as far as the law allows. Nothing in these terms limits your rights as a consumer.'
			]
		},
		{
			id: 'complaints',
			title: 'Questions, complaints and disputes',
			body: [
				'For questions or complaints, write to jeroen@appmire.be; we answer within two working days. If we can’t solve it together, you can contact the Consumer Mediation Service at https://consumentenombudsdienst.be.',
				'Belgian law applies. Disputes go to the competent Belgian courts, without depriving you of the protection of the law of the country where you live.'
			]
		},
		{
			id: 'privacy',
			title: 'Your personal data',
			body: [
				'We use your details only to deliver and handle your order and keep them as long as accounting law requires. Read more in our privacy policy at https://kinframe.appmire.be/privacy.'
			]
		}
	]
};

export default doc;
