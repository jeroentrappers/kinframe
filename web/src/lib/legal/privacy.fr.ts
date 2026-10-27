import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Politique de confidentialité',
	updated: '2026-10-27',
	intro: [
		'kinframe permet aux familles d’envoyer des photos, des vidéos et des messages vers un cadre photo à la maison. Cette politique explique quelles données personnelles le service kinframe hébergé sur https://kinframe.appmire.be collecte, pourquoi, qui d’autre intervient, combien de temps elles sont conservées et comment les faire supprimer. Elle couvre le site web, les applications et cadres kinframe lorsqu’ils utilisent ce serveur, les pages de connexion sur auth.kinframe.appmire.be et la boutique de précommande.',
		'En bref : nous utilisons vos données uniquement pour faire fonctionner le service. Pas de publicité, pas de mesure d’audience, pas de pistage, et rien n’est vendu ni partagé à des fins de marketing. Les serveurs se trouvent en Allemagne.'
	],
	sections: [
		{
			id: 'controller',
			title: 'Qui est responsable',
			body: [
				'Le responsable du traitement de vos données personnelles est Appmire CommV, Langestraat 116, 1790 Affligem, Belgique, numéro d’entreprise BE 0535.825.723 (« Appmire », « nous »). Pour toute question sur vos données, écrivez à jeroen@appmire.be.',
				'kinframe est un logiciel libre. Chacun peut faire tourner son propre serveur kinframe. Si vous utilisez un serveur géré par quelqu’un d’autre, c’est cette personne ou organisation qui est responsable de vos données sur ce serveur, et non Appmire, et cette politique ne s’y applique pas.'
			]
		},
		{
			id: 'data',
			title: 'Ce que nous collectons',
			body: [
				'Nous ne collectons que ce dont le service a besoin :',
				{
					list: [
						'Compte : votre adresse e-mail, le nom indiqué à l’inscription et un identifiant technique. Si vous vous connectez avec Apple, Google, Microsoft, Facebook ou GitHub, nous recevons votre nom, votre adresse e-mail (celle de Microsoft n’est pas utilisée) et l’identifiant que ce fournisseur vous attribue, et selon le fournisseur une photo de profil. Si vous utilisez une clé d’accès (passkey), nous conservons sa clé publique, jamais votre empreinte digitale ni votre visage. Nous ne recevons jamais les mots de passe de ces fournisseurs.',
						'Profil : votre nom d’affichage, une photo de profil facultative et votre langue.',
						'Familles et cadres : les cadres dont vous faites partie et votre rôle, les invitations et demandes d’adhésion (avec une note facultative), les noms et paramètres des cadres et, si le propriétaire l’indique, l’emplacement du cadre (un nom de lieu et ses coordonnées) pour la météo et les heures de lever du soleil.',
						'Ce que vous envoyez : photos et vidéos, légendes, albums, la date de prise de vue, le nom de fichier d’origine, et le moment où chaque élément est arrivé sur un cadre et y a été affiché pour la première fois. Les données de localisation et d’appareil photo (EXIF/GPS) sont supprimées de chaque photo et vidéo que nous conservons ; seule la date de prise de vue est gardée.',
						'Réactions et réponses : les cœurs et réponses rapides envoyés depuis un cadre.',
						'Agendas : les liens d’agenda (ICS ou CalDAV) que vous ajoutez, avec un nom d’utilisateur et un mot de passe facultatifs, et les événements qui en sont récupérés ; les événements et invitations que vous envoyez à un cadre (titre, lieu, notes, une photo facultative, votre nom). Les liens et mots de passe sont conservés chiffrés.',
						'Plug-ins (widgets sur le cadre) : les plug-ins qu’un cadre utilise et leurs réglages, comme l’adresse d’un appareil chez vous ou un jeton d’accès Home Assistant. Les jetons et autres secrets sont conservés chiffrés et envoyés uniquement au cadre. Pour les réglages des plug-ins dans les apps, le cadre signale aussi si chaque widget fonctionne, sa dernière erreur, les noms et adresses des appareils trouvés sur votre réseau domestique et, pour Home Assistant, les noms et unités de vos entités. Nous ne gardons que le dernier rapport.',
						'Données du cadre : marque et modèle, taille de l’écran, versions d’Android et de l’application, langue, fuseau horaire, utilisation du stockage, nom et force du signal du réseau Wi-Fi, durée de fonctionnement et nombre de plantages récents. Nous ne gardons que le dernier rapport. Nous ne collectons ni numéro de série, ni adresse MAC, ni niveau de batterie, ni la position du cadre.',
						'Notifications : si vous les activez, l’adresse de votre abonnement push (fournie par votre navigateur ou téléphone), un libellé d’appareil comme « Chrome sur Android » et votre langue.',
						'Réservations dans la boutique : votre nom, votre adresse e-mail, votre pays, un code postal et une remarque facultatifs, votre langue, les articles réservés et le moment de votre consentement. Aucune donnée de paiement et aucune adresse IP.',
						'Données techniques : nos serveurs voient votre adresse IP lorsque vous vous connectez. Le serveur kinframe l’utilise en mémoire pour limiter les abus (limitation du débit) et ne la conserve pas avec vos données. Le serveur web et le service de connexion tiennent des journaux (voir « Combien de temps nous conservons les données »).'
					]
				}
			]
		},
		{
			id: 'purposes',
			title: 'Pourquoi nous les utilisons, et sur quelle base légale',
			body: [
				{
					list: [
						'Pour fournir le service auquel vous vous êtes inscrit : votre compte, la livraison de vos envois aux bons cadres, l’affichage de l’expéditeur, les réactions, les agendas, la météo et les notifications (exécution d’un contrat, article 6, paragraphe 1, b du RGPD).',
						'Pour garder le service sûr et opérationnel : sécurité de la connexion, prévention des abus, correction des pannes et mise à jour des cadres (notre intérêt légitime, article 6, paragraphe 1, f).',
						'Pour vous envoyer des codes de connexion et, pour la boutique, la confirmation de votre réservation et son suivi (contrat ou mesures précontractuelles, et votre consentement dans le formulaire de réservation).',
						'Les notifications push uniquement si vous les activez ; vous pouvez les désactiver à tout moment (consentement, article 6, paragraphe 1, a).',
						'Pour respecter nos obligations légales, par exemple lorsque les autorités nous demandent légalement des informations (article 6, paragraphe 1, c).'
					]
				},
				'Nous n’utilisons pas vos données pour de la publicité ou du profilage, et ne prenons aucune décision automatisée vous concernant. Nous ne regardons pas vos photos, sauf si c’est nécessaire pour traiter un signalement de contenu illicite ou un problème technique que vous nous signalez.'
			]
		},
		{
			id: 'sharing',
			title: 'Qui peut voir vos données',
			body: [
				'Au sein de kinframe : les membres d’un cadre voient les noms d’affichage et photos de profil des autres membres, ainsi que ce qui est envoyé à ce cadre. Votre adresse e-mail n’est pas montrée aux autres membres. Les personnes que vous invitez voient votre nom avec l’invitation. Les propriétaires et admins voient aussi les paramètres et l’état du cadre. Et les personnes qui voient le cadre chez vous voient bien sûr ce qu’il affiche.',
				'Prestataires et tiers avec lesquels nous travaillons :',
				{
					list: [
						'Hetzner Online GmbH (Allemagne) héberge notre serveur. La base de données et toutes les photos et vidéos sont stockées sur ce serveur en Allemagne, les médias dans un stockage objet que nous gérons nous-mêmes.',
						'Open-Meteo (open-meteo.com) fournit les prévisions météo et la recherche de lieux. Notre serveur lui envoie les coordonnées de l’emplacement d’un cadre et les noms de lieux que vous recherchez, jamais votre identité. Les cadres ne le contactent eux-mêmes que pour les plug-ins qui l’utilisent (voir « Plug-ins » ci-dessous).',
						'Services push : pour délivrer une notification, notre serveur l’envoie au service push de votre navigateur ou téléphone (par exemple Google, Mozilla ou Apple pour les notifications web, ou le distributeur UnifiedPush que vous avez choisi sur Android). Les notifications contiennent de courts textes comme un nom de cadre, le nom d’une personne qui demande à rejoindre ou le titre d’un événement, mais jamais de photos ni de légendes. Les notifications web sont chiffrées de bout en bout ; les messages UnifiedPush sont envoyés en HTTPS à votre distributeur, qui peut les lire.',
						'Fournisseurs de connexion : uniquement si vous choisissez de vous connecter avec Apple, Google, Microsoft, Facebook (Meta) ou GitHub. Ce fournisseur sait alors que vous utilisez kinframe, selon sa propre politique de confidentialité.',
						'Hébergeurs d’agenda : si vous ajoutez un lien d’agenda, notre serveur le récupère à l’adresse indiquée (par exemple Google Agenda ou iCloud) avec les identifiants que vous avez saisis.',
						'Plug-ins : un widget ajouté par le propriétaire du cadre ne peut contacter que ce qu’il indique avant d’être ajouté. Les widgets pour des appareils chez vous (HomeWizard, Home Assistant) restent sur votre réseau domestique : rien de ce qu’ils lisent n’en sort. D’autres contactent directement depuis le cadre un service sur Internet : le radar de pluie interroge Buienradar (buienradar.nl) ou Open-Meteo sur la pluie à l’emplacement du cadre, arrondi à environ un kilomètre, et les plug-ins de la communauté contactent les hôtes qu’ils indiquent. Ces services voient l’adresse Internet du cadre et ce que le widget demande, jamais qui vous êtes, et le traitent selon leur propre politique de confidentialité. Les plug-ins de la communauté sont écrits par d’autres personnes et publiés dans un registre public ; nous les vérifions avant publication et pouvons les retirer.',
						'Google Cast : uniquement si vous choisissez d’afficher un diaporama sur un Chromecast, votre navigateur charge le logiciel Cast de Google et le Chromecast charge les photos depuis notre serveur.',
						'E-mail : les codes de connexion et les e-mails de la boutique passent par le propre serveur de messagerie d’Appmire. Ils ne contiennent aucun pixel de suivi.',
						'GitHub : notre serveur télécharge les mises à jour des applications kinframe et le registre des plug-ins de la communauté depuis GitHub Releases. Aucune donnée personnelle n’est envoyée ; les cadres téléchargent les mises à jour uniquement depuis notre serveur.',
						'Boutique : les réservations sont traitées par le vendeur indiqué sur la page de la boutique et dans l’e-mail de confirmation, qui les utilise uniquement pour cette réservation.'
					]
				},
				'Nous ne vendons pas vos données et ne les partageons avec personne d’autre, sauf obligation légale.'
			]
		},
		{
			id: 'transfers',
			title: 'Transferts hors de l’UE',
			body: [
				'Nous conservons et traitons vos données dans l’Union européenne. Certains fournisseurs que vous choisissez d’utiliser, comme un fournisseur de connexion, un service push ou votre hébergeur d’agenda, peuvent traiter des données hors de l’UE selon leurs propres conditions ; pour les fournisseurs établis aux États-Unis, cela relève généralement du cadre de protection des données UE-États-Unis ou de clauses contractuelles types.'
			]
		},
		{
			id: 'retention',
			title: 'Combien de temps nous conservons les données',
			body: [
				{
					list: [
						'Votre compte et votre profil : tant que vous avez un compte.',
						'Photos et vidéos : tant qu’elles sont sur un cadre ou dans sa photothèque. Les éléments supprimés vont dans la corbeille du cadre et sont retirés définitivement après 30 jours, ou plus tôt si quelqu’un les supprime définitivement. Le nettoyage automatique des fichiers stockés eux-mêmes n’existe pas encore : d’ici là, une photo retirée n’est plus montrée ni proposée à personne, mais son fichier stocké n’est effacé que lorsque nous faisons le nettoyage ou que vous nous le demandez (voir « Supprimer vos données »).',
						'Téléversements : le fichier que vous envoyez est supprimé dès qu’il a été traité ; les envois inachevés après 24 heures.',
						'Données du cadre : seul le dernier rapport est conservé ; chaque nouveau rapport remplace le précédent.',
						'Abonnements push : jusqu’à ce que vous désactiviez les notifications, vous déconnectiez de l’application ou que le service push signale que l’abonnement a expiré.',
						'Plug-ins : leurs réglages jusqu’à ce que le propriétaire supprime le widget ou le cadre ; le rapport des plug-ins du cadre : uniquement le dernier.',
						'Agendas : jusqu’à ce que vous supprimiez l’agenda ou le cadre.',
						'Réservations dans la boutique : supprimées automatiquement un an après la dernière modification, ou 30 jours après votre annulation.',
						'Journaux : en fonctionnement normal, le serveur kinframe n’enregistre ni les requêtes ni les adresses IP. Notre serveur web conserve des journaux d’accès (adresse IP, heure, adresse demandée, navigateur) pendant 14 jours au maximum, pour la sécurité. Le service de connexion garde une trace des connexions (heure, adresse IP et navigateur) pour protéger votre compte.'
					]
				}
			]
		},
		{
			id: 'delete',
			title: 'Supprimer vos données',
			body: [
				'Vous pouvez supprimer vous-même des données dans l’application ou sur le site web :',
				{
					list: [
						'Une photo ou une vidéo : supprimez-la de la photothèque du cadre (si vous avez le droit de la gérer), ou annulez un envoi depuis votre historique. Elle disparaît du cadre et va dans la corbeille pendant 30 jours.',
						'Quitter un cadre : vous pouvez quitter un cadre et choisir de retirer tout ce que vous y avez envoyé. Les propriétaires et admins peuvent de la même façon retirer un membre et ses éléments.',
						'Un cadre : le propriétaire peut supprimer un cadre. Il est déconnecté, plus personne n’y a accès et ses photos ne sont plus affichées.',
						'Notifications : désactivez-les dans les paramètres ; l’abonnement push est supprimé.'
					]
				},
				'Supprimer votre compte : les applications n’ont pas encore de bouton pour cela. Envoyez un e-mail à jeroen@appmire.be depuis l’adresse avec laquelle vous vous connectez, avec l’objet « Supprimer mon compte kinframe », en précisant si les photos que vous avez envoyées doivent rester sur les cadres ou être retirées. Nous supprimons votre compte, votre identité de connexion (y compris un lien avec Apple, Google, Microsoft, Facebook ou GitHub), votre photo de profil et vos abonnements push, et vos photos si vous le demandez. Si vous êtes propriétaire d’un cadre, dites-nous s’il faut le supprimer ou le confier à un autre membre. Nous le faisons dans un délai d’un mois et le confirmons par e-mail. Les éléments que vous laissez sur un cadre continuent d’afficher votre nom.',
				'Effacer complètement les fichiers : si vous voulez que les fichiers stockés des photos que vous avez envoyées, ou toutes les données d’un cadre supprimé, soient effacés de nos serveurs (et pas seulement retirés de l’affichage), demandez-le-nous à la même adresse et nous le ferons dans un délai d’un mois.',
				'Si vous vous êtes connecté avec Facebook, vous pouvez aussi retirer kinframe de votre compte Facebook via Paramètres et confidentialité → Paramètres → Apps et sites web. Facebook cesse alors de partager des données avec nous ; pour supprimer les données que nous avons déjà, envoyez-nous l’e-mail ci-dessus. Il en va de même pour Apple, Google, Microsoft et GitHub dans leurs paramètres de compte.',
				'Nos sauvegardes sont écrasées au fil du cycle normal de sauvegarde, de sorte que les données supprimées en disparaissent aussi avec le temps.'
			]
		},
		{
			id: 'rights',
			title: 'Vos droits',
			body: [
				'En vertu du RGPD, vous avez le droit d’accéder à vos données et d’en obtenir une copie, de les faire rectifier ou effacer, d’en limiter l’utilisation ou de vous y opposer, à la portabilité, et de retirer votre consentement à tout moment (sans effet sur ce qui a été fait auparavant). Pour exercer ces droits, écrivez à jeroen@appmire.be. Nous pouvons vous demander de confirmer que la demande vient bien de vous, et nous répondons dans un délai d’un mois.',
				'Vous pouvez aussi introduire une plainte auprès de l’Autorité de protection des données (Gegevensbeschermingsautoriteit), rue de la Presse 35, 1000 Bruxelles, contact@apd-gba.be, https://www.autoriteprotectiondonnees.be. Nous apprécierions toutefois d’avoir d’abord l’occasion de régler la question avec vous.'
			]
		},
		{
			id: 'storage',
			title: 'Cookies et stockage sur votre appareil',
			body: [
				'Le site kinframe ne dépose aucun cookie propre et n’utilise ni mesure d’audience ni pistage. Il conserve dans votre navigateur quelques éléments nécessaires à son fonctionnement : votre langue, votre session de connexion (gérée par le logiciel de connexion) et votre réglage de notifications. Les pages de connexion sur auth.kinframe.appmire.be utilisent des cookies strictement nécessaires pour la session de connexion. Toutes les polices et tous les scripts viennent de notre propre serveur ; rien n’est chargé depuis des tiers, sauf le logiciel Cast de Google lorsque vous lancez une diffusion.',
				'Les applications conservent votre connexion de manière sécurisée sur votre appareil. Le cadre garde ses photos et vidéos en cache pour fonctionner hors ligne.'
			]
		},
		{
			id: 'security',
			title: 'Sécurité',
			body: [
				'Toutes les connexions utilisent HTTPS. Les cadres n’établissent que des connexions sortantes et s’authentifient avec leur propre clé révocable. Les liens et mots de passe d’agenda sont chiffrés dans la base de données, le stockage des médias n’est pas accessible depuis Internet et les photos ne sont servies que via des liens signés de courte durée. Seul Appmire a accès aux serveurs. Si une violation de données menace vos droits, nous vous en informons, ainsi que l’autorité, comme la loi l’exige.'
			]
		},
		{
			id: 'children',
			title: 'Enfants',
			body: [
				'Vous devez avoir au moins 13 ans pour créer un compte kinframe. Les familles envoient souvent des photos d’enfants : n’envoyez des photos d’autres personnes, et surtout d’enfants, que si vous y êtes autorisé et qu’elles ou leurs parents seraient d’accord. Un cadre n’a pas besoin de compte, donc tout le monde peut le regarder.'
			]
		},
		{
			id: 'changes',
			title: 'Modifications de cette politique',
			body: [
				'Si nous modifions cette politique, nous mettons à jour la date en haut de la page. Lorsqu’une modification compte pour l’utilisation de vos données, nous vous prévenons à l’avance par e-mail ou dans l’application.'
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
