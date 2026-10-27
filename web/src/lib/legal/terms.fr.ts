import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Conditions d’utilisation',
	updated: '2026-10-27',
	intro: [
		'Ces conditions s’appliquent au service kinframe hébergé sur https://kinframe.appmire.be : le site web, les pages de connexion, et les applications et cadres kinframe lorsqu’ils utilisent ce serveur. En créant un compte ou en utilisant le service, vous les acceptez. Lisez aussi notre politique de confidentialité sur https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'provider',
			title: 'Qui fournit le service',
			body: [
				'Le service est fourni par Appmire CommV, Langestraat 116, 1790 Affligem, Belgique, numéro d’entreprise BE 0535.825.723 (« Appmire », « nous »). Contact : jeroen@appmire.be.'
			]
		},
		{
			id: 'service',
			title: 'Le service',
			body: [
				'kinframe vous permet d’envoyer des photos, des vidéos, des légendes et des événements vers les cadres photo de votre famille, et de voir quand ils arrivent et comment vos proches réagissent. Le service est actuellement gratuit. Nous pouvons ajouter, modifier ou retirer des fonctions au fil de l’évolution du logiciel.',
				'Le logiciel kinframe est un logiciel libre sous licence GNU Affero General Public License (AGPL-3.0) ; son code source se trouve sur https://github.com/jeroentrappers/kinframe. Ces conditions concernent notre service hébergé, pas le logiciel : elles ne limitent pas les droits que vous confère l’AGPL, et vous êtes libre de faire tourner votre propre serveur kinframe.'
			]
		},
		{
			id: 'account',
			title: 'Votre compte',
			body: [
				{
					list: [
						'Vous devez avoir au moins 13 ans pour créer un compte.',
						'Utilisez une adresse e-mail qui vous appartient et protégez votre connexion (e-mail, clés d’accès et éventuel compte Apple, Google, Microsoft, Facebook ou GitHub associé). Vous êtes responsable de ce qui est fait avec votre compte.',
						'Un compte est destiné à une seule personne. Un cadre n’a pas besoin de compte.',
						'Les propriétaires d’un cadre décident qui peut le rejoindre et ce que les membres peuvent faire. Les propriétaires et admins sont responsables des personnes qu’ils invitent.'
					]
				}
			]
		},
		{
			id: 'content',
			title: 'Votre contenu',
			body: [
				'Vous conservez tous les droits sur les photos, vidéos, légendes et autres contenus que vous envoyez. Vous accordez à Appmire uniquement l’autorisation nécessaire pour faire fonctionner le service : stocker votre contenu, en faire des copies à la bonne taille pour chaque cadre, et le montrer aux cadres et personnes à qui vous l’envoyez ou avec qui vous le partagez. Cette autorisation prend fin lorsque votre contenu est supprimé, sauf pour les copies conservées pendant une durée limitée dans les sauvegardes.',
				'N’envoyez que des contenus que vous avez le droit de partager, qui sont légaux et que les personnes représentées accepteraient. Soyez particulièrement attentif aux photos d’enfants et de personnes qui ne peuvent pas facilement s’y opposer.'
			]
		},
		{
			id: 'plugins',
			title: 'Plug-ins',
			body: [
				'Les propriétaires et administrateurs d’un cadre peuvent ajouter des plug-ins : de petits widgets qui affichent des informations provenant d’appareils de la maison ou de services sur Internet. Les plug-ins intégrés font partie de kinframe. Les plug-ins de la communauté sont créés par d’autres personnes et publiés dans le registre public https://github.com/jeroentrappers/kinframe-plugins sous leurs propres licences libres.',
				'Nous vérifions chaque plug-in de la communauté avant sa publication et pouvons le retirer à tout moment, mais nous ne garantissons pas qu’un plug-in, ou le service qu’il utilise, fonctionne, soit exact ou reste disponible. Ce qu’affiche un plug-in (consommation d’énergie, météo, pluie, départs, prix) n’est donné qu’à titre pratique : ne vous y fiez pas pour des décisions de sécurité, de santé ou d’argent.',
				'Un plug-in qui utilise un service sur Internet fait de vous un utilisateur de ce service, selon ses propres conditions. N’indiquez que des jetons et mots de passe de comptes et d’appareils qui sont à vous.',
			]
		},
		{
			id: 'use',
			title: 'Utilisation acceptable',
			body: [
				'Vous ne pouvez pas utiliser le service pour :',
				{
					list: [
						'stocker ou envoyer des contenus illicites, notamment du matériel d’abus sexuel sur enfants, des contenus qui portent atteinte au droit d’auteur ou à la vie privée d’autrui, ou des contenus qui incitent à la violence ou à la haine ;',
						'harceler, menacer ou tromper d’autres personnes ;',
						'envoyer du spam, des logiciels malveillants ou tout ce qui vise à nuire à des appareils ou à des personnes ;',
						'perturber ou surcharger le service, contourner ses limites, ou accéder à des comptes, cadres ou données qui ne sont pas les vôtres ;',
						'exploiter un service commercial pour des tiers par-dessus notre service hébergé, ou stocker de grandes quantités de données sans rapport avec des cadres photo.'
					]
				}
			]
		},
		{
			id: 'report',
			title: 'Signaler un contenu illicite',
			body: [
				'Si vous trouvez sur le service un contenu que vous estimez illicite, signalez-le à jeroen@appmire.be. Indiquez où vous l’avez vu et pourquoi vous le pensez illicite. Nous traitons les signalements avec soin et rapidement, pouvons retirer le contenu ou en bloquer l’accès, et en informons la personne qui l’a envoyé, sauf si la loi ou une enquête ne le permet pas. Cette adresse est aussi notre point de contact pour les autorités et les utilisateurs au titre du règlement européen sur les services numériques (DSA), en français, néerlandais ou anglais.'
			]
		},
		{
			id: 'suspension',
			title: 'Suspension et fin',
			body: [
				'Si vous enfreignez ces conditions ou la loi, ou mettez en danger le service ou d’autres personnes, nous pouvons retirer des contenus, limiter votre accès ou suspendre ou fermer votre compte. Sauf urgence ou interdiction légale, nous vous prévenons d’abord en expliquant pourquoi ; si vous n’êtes pas d’accord, répondez à jeroen@appmire.be.',
				'Vous pouvez cesser d’utiliser le service à tout moment et nous demander de supprimer votre compte (voir la politique de confidentialité). Si nous décidons d’arrêter complètement le service, nous vous prévenons au moins 60 jours à l’avance, pour que vous ayez le temps de sauvegarder vos photos et de passer à un autre serveur kinframe.'
			]
		},
		{
			id: 'availability',
			title: 'Disponibilité',
			body: [
				'Nous faisons de notre mieux pour que le service fonctionne et que votre contenu soit en sécurité, mais le service est gratuit et fourni « en l’état ». Nous ne pouvons pas promettre qu’il sera toujours disponible ou exempt d’erreurs, ni qu’aucun contenu ne sera jamais perdu. Gardez vos propres copies des photos et vidéos qui comptent pour vous ; kinframe n’est pas un service de sauvegarde.'
			]
		},
		{
			id: 'liability',
			title: 'Responsabilité',
			body: [
				'Dans la mesure permise par le droit belge, Appmire n’est pas responsable des dommages indirects, comme la perte de données, le manque à gagner ou les dommages causés par des contenus envoyés par d’autres utilisateurs, ni des interruptions du service. Cela ne limite pas notre responsabilité pour faute intentionnelle ou faute grave, en cas de décès ou de dommage corporel, ni toute autre responsabilité que la loi ne permet pas d’exclure. Vos droits impératifs de consommateur ne sont pas affectés.'
			]
		},
		{
			id: 'shop',
			title: 'Boutique de précommande',
			body: [
				'Lorsque le service propose des cadres en précommande, une réservation n’est pas un achat : vous ne payez rien et n’êtes pas tenu d’acheter. Le vendeur est indiqué sur la page de la boutique et dans votre e-mail de confirmation. Lorsque les cadres sont en stock, le vendeur vous envoie par e-mail le prix définitif, la date de livraison et un lien de paiement, et ce n’est que si vous décidez alors d’acheter qu’une vente a lieu, selon les conditions de vente du vendeur et avec vos droits légaux de consommateur, dont le droit de rétractation. Vous pouvez annuler une réservation à tout moment avec le lien de votre e-mail de confirmation.'
			]
		},
		{
			id: 'changes',
			title: 'Modifications de ces conditions',
			body: [
				'Nous pouvons modifier ces conditions, par exemple lorsque le service ou la loi change. Nous annonçons les modifications importantes au moins 30 jours à l’avance par e-mail ou dans l’application. Si vous n’acceptez pas une modification, vous pouvez cesser d’utiliser le service et faire supprimer votre compte avant son entrée en vigueur.'
			]
		},
		{
			id: 'law',
			title: 'Droit applicable et litiges',
			body: [
				'Ces conditions sont régies par le droit belge. Contactez-nous d’abord à jeroen@appmire.be ; nous chercherons une solution ensemble. À défaut, les tribunaux belges compétents tranchent, sans préjudice de votre droit, en tant que consommateur, de saisir les tribunaux de votre lieu de résidence et de la protection du droit impératif de votre pays.'
			]
		},
		{
			id: 'contact',
			title: 'Contact',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, Belgique. Numéro d’entreprise BE 0535.825.723. E-mail : jeroen@appmire.be.'
			]
		}
	]
};

export default doc;
