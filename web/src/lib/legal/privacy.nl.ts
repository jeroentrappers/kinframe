import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Privacybeleid',
	updated: '2026-10-27',
	intro: [
		'Met kinframe sturen families foto’s, video’s en berichten naar een fotokader thuis. Dit beleid legt uit welke persoonsgegevens de gehoste kinframe-dienst op https://kinframe.appmire.be verzamelt, waarom, wie er nog bij betrokken is, hoelang we ze bewaren en hoe je ze kunt laten wissen. Het geldt voor de website, de kinframe-apps en kaders wanneer ze deze server gebruiken, de aanmeldpagina’s op auth.kinframe.appmire.be en de webshop voor reservaties.',
		'Kort gezegd: we gebruiken je gegevens alleen om de dienst te laten werken. Geen advertenties, geen analytics en geen tracking, en we verkopen of delen niets voor marketing. De servers staan in Duitsland.'
	],
	sections: [
		{
			id: 'controller',
			title: 'Wie is verantwoordelijk',
			body: [
				'Verwerkingsverantwoordelijke voor je persoonsgegevens is Appmire CommV, Langestraat 116, 1790 Affligem, België, ondernemingsnummer BE 0535.825.723 (“Appmire”, “wij”). Voor alle vragen over je gegevens mail je naar jeroen@appmire.be.',
				'kinframe is opensourcesoftware. Iedereen kan een eigen kinframe-server draaien. Gebruik je een server van iemand anders, dan is die persoon of organisatie verantwoordelijk voor je gegevens daar, niet Appmire, en geldt dit beleid daar niet.'
			]
		},
		{
			id: 'data',
			title: 'Wat we verzamelen',
			body: [
				'We verzamelen alleen wat de dienst nodig heeft:',
				{
					list: [
						'Account: je e-mailadres, de naam die je bij het aanmelden opgeeft en een technisch gebruikers-ID. Meld je je aan met Apple, Google, Microsoft, Facebook of GitHub, dan krijgen we je naam, je e-mailadres (van Microsoft gebruiken we het e-mailadres niet) en het ID dat die aanbieder voor je gebruikt, en afhankelijk van de aanbieder een profielfoto. Gebruik je een passkey, dan bewaren we de publieke sleutel ervan, nooit je vingerafdruk of gezicht. Wachtwoorden van die aanbieders krijgen we nooit.',
						'Profiel: je weergavenaam, een optionele profielfoto en je taal.',
						'Families en kaders: bij welke kaders je hoort en je rol, uitnodigingen en aanvragen om lid te worden (met een optionele notitie), namen en instellingen van kaders en, als de eigenaar die instelt, de locatie van het kader (een plaatsnaam en de coördinaten ervan) voor het weer en de zonsopgang.',
						'Wat je stuurt: foto’s en video’s, onderschriften, albums, het moment waarop een foto genomen is, de oorspronkelijke bestandsnaam, en wanneer elk item op een kader aankwam en voor het eerst getoond werd. Locatie- en cameragegevens (EXIF/GPS) worden verwijderd uit elke foto en video die we bewaren; alleen het opnametijdstip blijft.',
						'Reacties en antwoorden: hartjes en snelle antwoorden die vanaf een kader gestuurd worden.',
						'Agenda’s: agendalinks (ICS of CalDAV) die je toevoegt, met een optionele gebruikersnaam en wachtwoord, en de afspraken die daaruit opgehaald worden; afspraken en uitnodigingen die je naar een kader stuurt (titel, plaats, notities, een optionele foto, je naam). Links en wachtwoorden worden versleuteld bewaard.',
						'Plug-ins (widgets op het kader): welke plug-ins een kader gebruikt en hun instellingen, zoals het adres van een toestel bij je thuis of een toegangstoken voor Home Assistant. Tokens en andere geheimen worden versleuteld bewaard en alleen naar het kader gestuurd. Voor de plug-ininstellingen in de apps meldt het kader ook of elke widget werkt, de laatste fout, de namen en adressen van toestellen die het in je thuisnetwerk vond en, voor Home Assistant, de namen en eenheden van je entiteiten. We bewaren alleen de laatste melding.',
						'Gegevens van het kader: merk en model, schermgrootte, Android- en appversie, taal, tijdzone, opslaggebruik, de naam en signaalsterkte van het wifinetwerk, de tijd sinds het opstarten en het aantal recente crashes. We bewaren alleen het laatste rapport. We verzamelen geen serienummers, MAC-adressen, batterijniveaus of de positie van het kader.',
						'Meldingen: als je ze aanzet, het adres van je pushabonnement (van je browser of telefoon), een toestellabel zoals “Chrome op Android” en je taal.',
						'Reservaties in de webshop: je naam, e-mailadres, land, een optionele postcode en opmerking, je taal, wat je reserveert en het moment waarop je toestemming gaf. Geen betaalgegevens en geen IP-adressen.',
						'Technische gegevens: onze servers zien je IP-adres als je verbinding maakt. De kinframe-server gebruikt het in het geheugen om misbruik te beperken (rate limits) en bewaart het niet bij je gegevens. De webserver en de aanmelddienst houden logs bij (zie “Hoelang we gegevens bewaren”).'
					]
				}
			]
		},
		{
			id: 'purposes',
			title: 'Waarom we ze gebruiken, en op welke rechtsgrond',
			body: [
				{
					list: [
						'Om de dienst te leveren waarvoor je je aanmeldde: je account, wat je stuurt bij de juiste kaders afleveren, tonen wie het stuurde, reacties, agenda’s, het weer en meldingen (uitvoering van een overeenkomst, artikel 6, lid 1, b AVG).',
						'Om de dienst veilig en werkend te houden: veilig aanmelden, misbruik voorkomen, fouten oplossen en kaders up-to-date houden (ons gerechtvaardigd belang, artikel 6, lid 1, f).',
						'Om je aanmeldcodes te sturen en, voor de webshop, de bevestiging van je reservatie en de opvolging ervan (overeenkomst of stappen vóór een overeenkomst, en je toestemming in het reservatieformulier).',
						'Pushmeldingen alleen als je ze aanzet; je kunt ze altijd weer uitzetten (toestemming, artikel 6, lid 1, a).',
						'Om aan wettelijke verplichtingen te voldoen, bijvoorbeeld als de overheid rechtmatig om informatie vraagt (artikel 6, lid 1, c).'
					]
				},
				'We gebruiken je gegevens niet voor reclame of profilering en nemen geen geautomatiseerde beslissingen over je. We bekijken je foto’s niet, behalve als dat nodig is om een melding van illegale inhoud of een technisch probleem dat jij meldt af te handelen.'
			]
		},
		{
			id: 'sharing',
			title: 'Wie je gegevens kan zien',
			body: [
				'Binnen kinframe: de leden van een kader zien de weergavenamen en profielfoto’s van de andere leden en wat naar dat kader gestuurd wordt. Je e-mailadres wordt niet aan andere leden getoond. Wie je uitnodigt, ziet je naam bij de uitnodiging. Eigenaars en beheerders zien ook de instellingen en de toestand van het kader. En wie het kader bij jou thuis ziet staan, ziet natuurlijk wat het toont.',
				'Dienstverleners en derden waarmee we werken:',
				{
					list: [
						'Hetzner Online GmbH (Duitsland) host onze server. De database en alle foto’s en video’s staan op die server in Duitsland, de media in een objectopslag die we zelf beheren.',
						'Open-Meteo (open-meteo.com) levert weersverwachtingen en het zoeken naar plaatsen. Onze server stuurt de coördinaten van de locatie van een kader en de plaatsnamen die je zoekt, nooit wie je bent. Kaders contacteren het zelf alleen voor plug-ins die het gebruiken (zie “Plug-ins” hieronder).',
						'Pushdiensten: om een melding af te leveren stuurt onze server ze naar de pushdienst van je browser of telefoon (bijvoorbeeld Google, Mozilla of Apple voor webmeldingen, of de UnifiedPush-distributor die je op Android koos). Meldingen bevatten korte teksten zoals een kadernaam, de naam van iemand die lid wil worden of de titel van een afspraak, maar nooit foto’s of onderschriften. Webmeldingen zijn end-to-end versleuteld; UnifiedPush-berichten gaan via HTTPS naar je distributor, die ze kan lezen.',
						'Aanmeldaanbieders: alleen als je kiest om je aan te melden met Apple, Google, Microsoft, Facebook (Meta) of GitHub. Die aanbieder weet dan dat je kinframe gebruikt, onder zijn eigen privacybeleid.',
						'Agendadiensten: voeg je een agendalink toe, dan haalt onze server die op bij het adres dat je opgaf (bijvoorbeeld Google Agenda of iCloud), met de inloggegevens die je invulde.',
						'Plug-ins: een widget die de eigenaar van het kader toevoegt, mag alleen praten met wat hij vóór het toevoegen vermeldt. Widgets voor toestellen bij je thuis (HomeWizard, Home Assistant) blijven in je thuisnetwerk: niets wat ze lezen verlaat het. Andere contacteren rechtstreeks vanaf het kader een internetdienst: het weerstation leest de openbare pagina van je station bij Weathercloud (weathercloud.net), de buienradar vraagt Buienradar (buienradar.nl) of Open-Meteo naar de regen op de plaats van het kader, afgerond op ongeveer een kilometer, en communityplug-ins contacteren de hosts die ze vermelden. Die diensten zien het internetadres van het kader en wat de widget vraagt, nooit wie je bent, en behandelen dat volgens hun eigen privacybeleid. Communityplug-ins zijn geschreven door anderen en verschijnen in een openbaar register; we controleren ze vóór publicatie en kunnen ze intrekken.',
						'Google Cast: alleen als je kiest om een diavoorstelling op een Chromecast te tonen, laadt je browser de Cast-software van Google en haalt de Chromecast de foto’s bij onze server.',
						'E-mail: aanmeldcodes en e-mails van de webshop gaan via de eigen mailserver van Appmire. Ze bevatten geen trackingpixels.',
						'GitHub: onze server haalt updates van de kinframe-apps en het register met communityplug-ins op bij GitHub Releases. Daarbij worden geen persoonsgegevens verstuurd; kaders halen updates alleen bij onze server.',
						'Webshop: reservaties worden afgehandeld door de verkoper die op de shoppagina en in de bevestigingsmail vermeld staat, en die ze alleen voor die reservatie gebruikt.'
					]
				},
				'We verkopen je gegevens niet en delen ze met niemand anders, tenzij de wet het verplicht.'
			]
		},
		{
			id: 'transfers',
			title: 'Doorgifte buiten de EU',
			body: [
				'We bewaren en verwerken je gegevens in de Europese Unie. Sommige aanbieders die je zelf kiest, zoals een aanmeldaanbieder, een pushdienst of je agendadienst, kunnen gegevens buiten de EU verwerken onder hun eigen voorwaarden; voor aanbieders in de Verenigde Staten gebeurt dat meestal onder het EU-VS-gegevensprivacykader of standaardcontractbepalingen.'
			]
		},
		{
			id: 'retention',
			title: 'Hoelang we gegevens bewaren',
			body: [
				{
					list: [
						'Je account en profiel: zolang je een account hebt.',
						'Foto’s en video’s: zolang ze op een kader of in de foto’s van een kader staan. Verwijderde items gaan naar de prullenbak van het kader en worden na 30 dagen definitief verwijderd, of eerder als iemand ze definitief verwijdert. Het automatisch opruimen van de bewaarde bestanden zelf is nog niet gebouwd: tot dan wordt een verwijderde foto aan niemand meer getoond of aangeboden, maar het bewaarde bestand wordt pas gewist als wij opruimen of als je het ons vraagt (zie “Je gegevens wissen”).',
						'Uploads: het bestand dat je uploadt, wordt gewist zodra het verwerkt is; onvoltooide uploads na 24 uur.',
						'Gegevens van het kader: alleen het laatste rapport wordt bewaard; elk nieuw rapport vervangt het vorige.',
						'Pushabonnementen: tot je meldingen uitzet, je afmeldt in de app of de pushdienst laat weten dat het abonnement verlopen is.',
						'Plug-ins: hun instellingen tot de eigenaar de widget of het kader verwijdert; de plug-inmelding van het kader: alleen de laatste.',
						'Agenda’s: tot je de agenda of het kader verwijdert.',
						'Reservaties in de webshop: automatisch gewist een jaar na de laatste wijziging, of 30 dagen nadat je annuleert.',
						'Logs: de kinframe-server logt bij normale werking geen verzoeken of IP-adressen. Onze webserver houdt toegangslogs bij (IP-adres, tijdstip, opgevraagd adres, browser), hoogstens 14 dagen, voor de beveiliging. De aanmelddienst houdt aanmeldingen bij (tijdstip, IP-adres en browser) om je account te beschermen.'
					]
				}
			]
		},
		{
			id: 'delete',
			title: 'Je gegevens wissen',
			body: [
				'Je kunt zelf gegevens verwijderen in de app of op de website:',
				{
					list: [
						'Een foto of video: verwijder ze uit de foto’s van het kader (als je die mag beheren), of trek iets wat je stuurde in via je geschiedenis. Het verdwijnt van het kader en gaat 30 dagen naar de prullenbak.',
						'Een kader verlaten: je kunt een kader verlaten en kiezen om alles wat je ernaar stuurde te verwijderen. Eigenaars en beheerders kunnen op dezelfde manier een lid en zijn of haar items verwijderen.',
						'Een kader: de eigenaar kan een kader verwijderen. Het wordt losgekoppeld, niemand heeft er nog toegang toe en de foto’s worden niet meer getoond.',
						'Meldingen: zet ze uit bij Instellingen; het pushabonnement wordt gewist.'
					]
				},
				'Je account verwijderen: daar is in de apps nog geen knop voor. Stuur een e-mail naar jeroen@appmire.be vanaf het adres waarmee je je aanmeldt, met als onderwerp “Verwijder mijn kinframe-account”, en zeg of de foto’s die je stuurde op de kaders mogen blijven of verwijderd moeten worden. We verwijderen je account, je aanmeldidentiteit (ook een koppeling met Apple, Google, Microsoft, Facebook of GitHub), je profielfoto en je pushabonnementen, en op vraag je foto’s. Ben je eigenaar van een kader, laat dan weten of we het moeten verwijderen of aan een ander lid overdragen. We doen dit binnen een maand en bevestigen het per e-mail. Items die je op een kader laat staan, tonen nog steeds je naam.',
				'Bestanden volledig wissen: wil je dat de bewaarde bestanden van foto’s die je stuurde, of alle gegevens van een verwijderd kader, van onze servers gewist worden (en niet alleen uit beeld verdwijnen), vraag het ons op hetzelfde adres en we doen het binnen een maand.',
				'Meldde je je aan met Facebook, dan kun je kinframe ook uit je Facebook-account verwijderen via Instellingen en privacy → Instellingen → Apps en websites. Dan deelt Facebook geen gegevens meer met ons; om de gegevens te wissen die we al hebben, stuur je ons de e-mail hierboven. Hetzelfde geldt voor Apple, Google, Microsoft en GitHub in hun accountinstellingen.',
				'Onze back-ups worden in de normale back-upcyclus overschreven, zodat gewiste gegevens er na verloop van tijd ook uit verdwijnen.'
			]
		},
		{
			id: 'rights',
			title: 'Je rechten',
			body: [
				'Volgens de AVG heb je het recht om je gegevens in te zien en er een kopie van te krijgen, om ze te laten verbeteren of wissen, om het gebruik ervan te laten beperken of je ertegen te verzetten, op overdraagbaarheid, en om je toestemming altijd in te trekken (dat verandert niets aan wat daarvoor gebeurde). Mail naar jeroen@appmire.be om deze rechten te gebruiken. We kunnen je vragen te bevestigen dat het verzoek van jou komt, en we antwoorden binnen een maand.',
				'Je kunt ook een klacht indienen bij de Gegevensbeschermingsautoriteit (Autorité de protection des données), Drukpersstraat 35, 1000 Brussel, contact@apd-gba.be, https://www.gegevensbeschermingsautoriteit.be. We krijgen wel graag eerst de kans om het samen met jou op te lossen.'
			]
		},
		{
			id: 'storage',
			title: 'Cookies en opslag op je toestel',
			body: [
				'De kinframe-website zet zelf geen cookies en gebruikt geen analytics of tracking. Ze bewaart een paar dingen in je browser die nodig zijn om te werken: je taal, je aanmeldsessie (beheerd door de aanmeldsoftware) en je instelling voor meldingen. De aanmeldpagina’s op auth.kinframe.appmire.be gebruiken strikt noodzakelijke cookies voor de aanmeldsessie. Alle lettertypes en scripts komen van onze eigen server; er wordt niets bij derden geladen, behalve de Cast-software van Google als je begint te casten.',
				'De apps bewaren je aanmelding veilig op je toestel. Het kader houdt een cache van zijn foto’s en video’s bij, zodat het ook offline werkt.'
			]
		},
		{
			id: 'security',
			title: 'Beveiliging',
			body: [
				'Alle verbindingen gebruiken HTTPS. Kaders maken alleen uitgaande verbindingen en melden zich aan met een eigen sleutel die ingetrokken kan worden. Agendalinks en wachtwoorden zijn versleuteld in de database, de mediaopslag is niet bereikbaar vanaf het internet en foto’s worden alleen via ondertekende, kortlevende links geleverd. Alleen Appmire heeft toegang tot de servers. Brengt een datalek je rechten in gevaar, dan laten we het jou en de autoriteit weten zoals de wet vraagt.'
			]
		},
		{
			id: 'children',
			title: 'Kinderen',
			body: [
				'Je moet minstens 13 jaar zijn om een kinframe-account aan te maken. Families sturen vaak foto’s van kinderen: stuur foto’s van anderen, en zeker van kinderen, alleen als je dat mag en zij of hun ouders ermee akkoord zouden gaan. Een kader zelf heeft geen account nodig, dus iedereen kan ernaar kijken.'
			]
		},
		{
			id: 'changes',
			title: 'Wijzigingen in dit beleid',
			body: [
				'Als we dit beleid wijzigen, passen we de datum bovenaan aan. Is een wijziging belangrijk voor hoe we je gegevens gebruiken, dan laten we het je vooraf weten per e-mail of in de app.'
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
