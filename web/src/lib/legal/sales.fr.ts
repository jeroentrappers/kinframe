import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Conditions générales de vente',
	updated: '2026-11-14',
	intro: [
		'Ces conditions s’appliquent à tout achat dans la boutique en ligne Familijst (https://familijst.be). Lisez-les avant de commander : en commandant, vous les acceptez. Notre politique de confidentialité se trouve sur https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'seller',
			title: 'Qui vend',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, Belgique, numéro d’entreprise et de TVA BE 0535.825.723. E-mail : jeroen@appmire.be, téléphone : +32 497 05 33 10.'
			]
		},
		{
			id: 'products',
			title: 'Produits et prix',
			body: [
				'Nous vendons des cadres photo Wi-Fi sur lesquels l’application kinframe est déjà installée, prêts à l’emploi. Chaque page produit décrit le cadre et ses principales caractéristiques.',
				'Les prix sont en euros, TVA de 21 % comprise. Les éventuels frais de livraison sont indiqués avant le paiement. Le service kinframe sur le cadre est gratuit et sans abonnement ; son utilisation est régie par les conditions d’utilisation de kinframe sur https://kinframe.appmire.be/terms.'
			]
		},
		{
			id: 'order',
			title: 'Commande et paiement',
			body: [
				'Vous choisissez vos cadres, indiquez vos coordonnées et votre adresse de livraison, acceptez ces conditions et payez en ligne. Le contrat est conclu dès que votre paiement a réussi ; nous confirmons alors votre commande par e-mail.',
				'Les paiements sont traités par notre prestataire de paiement Mollie B.V. (Bancontact, cartes de paiement et les autres moyens affichés lors du paiement). Nous ne voyons jamais les données de votre carte.',
				'Si un prix sur le site est manifestement erroné, nous pouvons annuler la commande ; vous êtes alors intégralement remboursé.'
			]
		},
		{
			id: 'delivery',
			title: 'Livraison',
			body: [
				'Nous livrons uniquement en Belgique. Le délai de livraison prévu est indiqué dans la boutique et dans votre confirmation. Nous livrons au plus tard 30 jours après votre commande ; à défaut, vous pouvez annuler la commande et nous vous remboursons immédiatement.',
				'Le risque de perte ou d’endommagement vous est transféré lorsque vous, ou la personne que vous désignez, recevez le colis. Prévenez-nous dans les quelques jours si le colis arrive endommagé.'
			]
		},
		{
			id: 'withdrawal',
			title: 'Droit de rétractation (14 jours)',
			body: [
				'En tant que consommateur, vous pouvez vous rétracter de votre achat dans les 14 jours suivant la réception du cadre, sans donner de motif. Pour vous rétracter, utilisez le lien de votre e-mail de commande, ou informez-nous clairement par e-mail ou par lettre (vous pouvez utiliser le formulaire type ci-dessous).',
				'Renvoyez le cadre dans les 14 jours suivant votre rétractation, à l’adresse ci-dessus. Les frais directs de renvoi sont à votre charge. Manipulez le cadre comme vous le feriez en magasin ; vous êtes responsable de la dépréciation résultant de manipulations autres que celles nécessaires pour en constater le fonctionnement.',
				'Nous remboursons tout ce que vous avez payé, y compris les frais de livraison standard, dans les 14 jours suivant la réception de votre rétractation, avec le même moyen de paiement. Nous pouvons différer le remboursement jusqu’à la récupération du cadre ou jusqu’à ce que vous ayez prouvé son expédition.',
				'Formulaire type de rétractation (à compléter et renvoyer uniquement si vous souhaitez vous rétracter) :',
				{
					list: [
						'À Appmire CommV, Langestraat 116, 1790 Affligem, Belgique, jeroen@appmire.be :',
						'Je/Nous vous notifie/notifions par la présente ma/notre rétractation du contrat portant sur la vente du bien ci-dessous : …',
						'Commandé le … / reçu le …, numéro de commande …',
						'Nom et adresse du/des consommateur(s) : …',
						'Signature (uniquement sur papier) et date : …'
					]
				}
			]
		},
		{
			id: 'warranty',
			title: 'Garantie',
			body: [
				'Chaque cadre bénéficie de la garantie légale de conformité de deux ans à compter de la livraison. Si le cadre est défectueux, contactez-nous dès que possible et au plus tard deux mois après avoir constaté le défaut. Nous le réparons ou le remplaçons, ou vous remboursons si ce n’est pas possible. Les dommages dus à une mauvaise utilisation, à une chute ou à l’eau ne sont pas couverts.'
			]
		},
		{
			id: 'liability',
			title: 'Responsabilité',
			body: [
				'Nous sommes responsables des dommages causés par notre faute, comme le prévoit la loi. Nous ne sommes pas responsables des dommages indirects, comme la perte de données, dans la mesure permise par la loi. Rien dans ces conditions ne limite vos droits de consommateur.'
			]
		},
		{
			id: 'complaints',
			title: 'Questions, plaintes et litiges',
			body: [
				'Pour toute question ou plainte, écrivez à jeroen@appmire.be ; nous répondons dans les deux jours ouvrables. Si nous ne trouvons pas de solution ensemble, vous pouvez vous adresser au Service de Médiation pour le Consommateur via https://consumentenombudsdienst.be.',
				'Le droit belge s’applique. Les litiges relèvent des tribunaux belges compétents, sans vous priver de la protection du droit du pays où vous résidez.'
			]
		},
		{
			id: 'privacy',
			title: 'Vos données personnelles',
			body: [
				'Nous utilisons vos données uniquement pour livrer et traiter votre commande, et les conservons aussi longtemps que la législation comptable l’exige. Plus d’informations dans notre politique de confidentialité sur https://kinframe.appmire.be/privacy.'
			]
		}
	]
};

export default doc;
