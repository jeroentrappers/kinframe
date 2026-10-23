import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Gebruiksvoorwaarden',
	updated: '2026-10-23',
	intro: [
		'Deze voorwaarden gelden voor de gehoste kinframe-dienst op https://kinframe.appmire.be: de website, de aanmeldpagina’s en de kinframe-apps en kaders wanneer ze deze server gebruiken. Door een account aan te maken of de dienst te gebruiken, ga je ermee akkoord. Lees ook ons privacybeleid op https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'provider',
			title: 'Wie de dienst aanbiedt',
			body: [
				'De dienst wordt aangeboden door Appmire CommV, Langestraat 116, 1790 Affligem, België, ondernemingsnummer BE 0535.825.723 (“Appmire”, “wij”). Contact: jeroen@appmire.be.'
			]
		},
		{
			id: 'service',
			title: 'De dienst',
			body: [
				'Met kinframe stuur je foto’s, video’s, onderschriften en afspraken naar de fotokaders van je familie, en zie je wanneer ze aankomen en hoe mensen reageren. De dienst is momenteel gratis. We kunnen functies toevoegen, wijzigen of weghalen naarmate de software zich ontwikkelt.',
				'De kinframe-software is vrije, opensourcesoftware onder de GNU Affero General Public License (AGPL-3.0); de broncode staat op https://github.com/jeroentrappers/kinframe. Deze voorwaarden gaan over onze gehoste dienst, niet over de software: ze beperken de rechten die de AGPL je geeft niet, en je mag ook je eigen kinframe-server draaien.'
			]
		},
		{
			id: 'account',
			title: 'Je account',
			body: [
				{
					list: [
						'Je moet minstens 13 jaar zijn om een account aan te maken.',
						'Gebruik een e-mailadres dat van jou is en houd je aanmelding (e-mail, passkeys en een eventueel gekoppeld Apple-, Google-, Microsoft-, Facebook- of GitHub-account) veilig. Je bent verantwoordelijk voor wat er met je account gebeurt.',
						'Een account is voor één persoon. Een kader heeft geen eigen account nodig.',
						'Eigenaars van een kader beslissen wie lid mag worden en wat leden mogen doen. Eigenaars en beheerders zijn verantwoordelijk voor de mensen die ze uitnodigen.'
					]
				}
			]
		},
		{
			id: 'content',
			title: 'Je inhoud',
			body: [
				'Je behoudt alle rechten op de foto’s, video’s, onderschriften en andere inhoud die je stuurt. Je geeft Appmire alleen de toestemming die we nodig hebben om de dienst te laten werken: je inhoud bewaren, er versies in de juiste grootte voor elk kader van maken, en ze tonen aan de kaders en mensen naar wie je ze stuurt of met wie je ze deelt. Die toestemming stopt wanneer je inhoud gewist is, behalve voor kopieën die nog een beperkte tijd in back-ups staan.',
				'Stuur alleen inhoud die je mag delen, die legaal is en waarmee de mensen erop akkoord zouden gaan. Wees extra voorzichtig met foto’s van kinderen en van mensen die zich niet makkelijk kunnen verzetten.'
			]
		},
		{
			id: 'use',
			title: 'Aanvaardbaar gebruik',
			body: [
				'Je mag de dienst niet gebruiken om:',
				{
					list: [
						'inhoud te bewaren of te sturen die illegaal is, waaronder beelden van seksueel misbruik van kinderen, inhoud die iemands auteursrecht of privacy schendt, of inhoud die aanzet tot geweld of haat;',
						'anderen lastig te vallen, te bedreigen of te misleiden;',
						'spam, malware of iets anders te sturen dat toestellen of mensen schade wil berokkenen;',
						'de dienst te verstoren of te overbelasten, de limieten ervan te omzeilen, of toegang te zoeken tot accounts, kaders of gegevens die niet van jou zijn;',
						'bovenop onze gehoste dienst een commerciële dienst voor anderen te draaien, of grote hoeveelheden gegevens op te slaan die niets met fotokaders te maken hebben.'
					]
				}
			]
		},
		{
			id: 'report',
			title: 'Illegale inhoud melden',
			body: [
				'Kom je op de dienst inhoud tegen die volgens jou illegaal is, meld het dan aan jeroen@appmire.be. Zeg waar je het zag en waarom je denkt dat het illegaal is. We behandelen meldingen zorgvuldig en snel, kunnen de inhoud verwijderen of de toegang ertoe blokkeren, en laten het weten aan wie het stuurde, tenzij de wet of een onderzoek dat niet toelaat. Dit adres is ook ons contactpunt voor overheden en gebruikers onder de Europese digitaledienstenverordening (DSA), in het Nederlands, Frans of Engels.'
			]
		},
		{
			id: 'suspension',
			title: 'Opschorting en beëindiging',
			body: [
				'Overtreed je deze voorwaarden of de wet, of breng je de dienst of anderen in gevaar, dan kunnen we inhoud verwijderen, je toegang beperken of je account opschorten of sluiten. Tenzij het dringend is of de wet het niet toelaat, laten we het je eerst weten en leggen we uit waarom; ben je het er niet mee eens, antwoord dan aan jeroen@appmire.be.',
				'Je kunt altijd stoppen met de dienst en ons vragen je account te verwijderen (zie het privacybeleid). Besluiten we de dienst helemaal stop te zetten, dan laten we het je minstens 60 dagen vooraf weten, zodat je tijd hebt om je foto’s te bewaren en naar een andere kinframe-server over te stappen.'
			]
		},
		{
			id: 'availability',
			title: 'Beschikbaarheid',
			body: [
				'We doen ons best om de dienst draaiende en je inhoud veilig te houden, maar de dienst is gratis en wordt aangeboden “zoals hij is”. We kunnen niet beloven dat hij altijd beschikbaar of foutloos is, of dat inhoud nooit verloren kan gaan. Houd zelf een kopie van foto’s en video’s die belangrijk voor je zijn; kinframe is geen back-updienst.'
			]
		},
		{
			id: 'liability',
			title: 'Aansprakelijkheid',
			body: [
				'Voor zover het Belgische recht dat toelaat, is Appmire niet aansprakelijk voor indirecte schade, zoals verloren gegevens, gederfde winst of schade door inhoud die andere gebruikers sturen, of voor onderbrekingen van de dienst. Dat beperkt onze aansprakelijkheid niet voor opzet of grove fout, voor overlijden of lichamelijk letsel, of voor andere aansprakelijkheid die de wet niet laat uitsluiten. Je dwingende rechten als consument blijven onaangetast.'
			]
		},
		{
			id: 'shop',
			title: 'Webshop voor reservaties',
			body: [
				'Biedt de dienst kaders aan om te reserveren, dan is een reservatie geen aankoop: je betaalt niets en bent niet verplicht te kopen. De verkoper staat vermeld op de shoppagina en in je bevestigingsmail. Zodra de kaders op voorraad zijn, mailt de verkoper je de definitieve prijs, de leverdatum en een betaallink, en pas als je dan beslist te kopen, komt er een verkoop tot stand, onder de verkoopvoorwaarden van de verkoper en met je wettelijke rechten als consument, waaronder het herroepingsrecht. Je kunt een reservatie altijd annuleren met de link in je bevestigingsmail.'
			]
		},
		{
			id: 'changes',
			title: 'Wijzigingen in deze voorwaarden',
			body: [
				'We kunnen deze voorwaarden wijzigen, bijvoorbeeld als de dienst of de wet verandert. Belangrijke wijzigingen kondigen we minstens 30 dagen vooraf aan per e-mail of in de app. Ga je niet akkoord met een wijziging, dan kun je stoppen met de dienst en je account laten verwijderen voor ze ingaat.'
			]
		},
		{
			id: 'law',
			title: 'Toepasselijk recht en geschillen',
			body: [
				'Op deze voorwaarden is het Belgische recht van toepassing. Neem eerst contact met ons op via jeroen@appmire.be; we zoeken samen naar een oplossing. Lukt dat niet, dan beslissen de bevoegde Belgische rechtbanken, onverminderd je recht als consument om naar de rechtbank van je woonplaats te stappen en de bescherming van het dwingende recht van je land.'
			]
		},
		{
			id: 'contact',
			title: 'Contact',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, België. Ondernemingsnummer BE 0535.825.723. E-mail: jeroen@appmire.be.'
			]
		}
	]
};

export default doc;
