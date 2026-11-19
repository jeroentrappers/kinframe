import type { LegalDoc } from './types';

const doc: LegalDoc = {
	title: 'Algemene verkoopsvoorwaarden',
	updated: '2026-11-14',
	intro: [
		'Deze voorwaarden gelden voor elke aankoop in de webshop van Familijst (https://familijst.be). Lees ze voor je bestelt: door te bestellen aanvaard je ze. Ons privacybeleid staat op https://kinframe.appmire.be/privacy.'
	],
	sections: [
		{
			id: 'seller',
			title: 'Wie verkoopt',
			body: [
				'Appmire CommV, Langestraat 116, 1790 Affligem, België, ondernemings- en btw-nummer BE 0535.825.723. E-mail: jeroen@appmire.be, telefoon: +32 497 05 33 10.'
			]
		},
		{
			id: 'products',
			title: 'Producten en prijzen',
			body: [
				'We verkopen wifi-fotokaders waarop de kinframe-app al geïnstalleerd is, klaar voor gebruik. Elke productpagina beschrijft het kader en zijn belangrijkste kenmerken.',
				'Prijzen zijn in euro en inclusief 21% btw. Eventuele verzendkosten zie je voor je betaalt. De kinframe-dienst op het kader is gratis en zonder abonnement; voor het gebruik ervan gelden de kinframe-gebruiksvoorwaarden op https://kinframe.appmire.be/terms.'
			]
		},
		{
			id: 'order',
			title: 'Bestellen en betalen',
			body: [
				'Je kiest je kaders, vult je gegevens en leveradres in, aanvaardt deze voorwaarden en betaalt online. De overeenkomst komt tot stand zodra je betaling gelukt is; we bevestigen je bestelling dan per e-mail.',
				'Betalingen verlopen via onze betaaldienst Mollie B.V. (Bancontact, betaalkaarten en de andere methodes die je bij het afrekenen ziet). Je kaartgegevens zien wij nooit.',
				'Staat er een duidelijk foute prijs op de site, dan mogen we de bestelling annuleren; je krijgt dan je geld volledig terug.'
			]
		},
		{
			id: 'delivery',
			title: 'Levering',
			body: [
				'We leveren alleen in België. De verwachte levertermijn staat in de shop en in je bevestiging. We leveren uiterlijk 30 dagen na je bestelling; lukt dat niet, dan mag je de bestelling annuleren en betalen we je meteen terug.',
				'Het risico van verlies of beschadiging gaat over op jou wanneer jij, of iemand die je aanduidt, het pakket ontvangt. Laat ons binnen enkele dagen weten als het pakket beschadigd aankomt.'
			]
		},
		{
			id: 'withdrawal',
			title: 'Herroepingsrecht (14 dagen)',
			body: [
				'Als consument mag je je aankoop binnen 14 dagen nadat je het kader ontvangen hebt herroepen, zonder opgave van reden. Herroepen doe je met de link in je bestelmail, of door het ons duidelijk te laten weten per e-mail of brief (je mag het modelformulier hieronder gebruiken).',
				'Stuur het kader binnen 14 dagen na je herroeping terug naar het adres hierboven. De rechtstreekse kosten van het terugsturen zijn voor jou. Behandel het kader zoals je dat in een winkel zou doen; je bent aansprakelijk voor waardevermindering door meer gebruik dan nodig om te zien hoe het werkt.',
				'We betalen alles terug wat je betaald hebt, ook de standaard leveringskosten, binnen 14 dagen nadat we je herroeping ontvangen hebben, met hetzelfde betaalmiddel. We mogen wachten met terugbetalen tot we het kader terug hebben of tot je aantoont dat je het verstuurd hebt.',
				'Modelformulier voor herroeping (vul het alleen in en stuur het alleen terug als je wilt herroepen):',
				{
					list: [
						'Aan Appmire CommV, Langestraat 116, 1790 Affligem, België, jeroen@appmire.be:',
						'Ik/Wij deel/delen u hierbij mede dat ik/wij onze overeenkomst betreffende de verkoop van de volgende goederen herroep/herroepen: …',
						'Besteld op … / ontvangen op …, bestelnummer …',
						'Naam en adres van de consument(en): …',
						'Handtekening (alleen op papier) en datum: …'
					]
				}
			]
		},
		{
			id: 'warranty',
			title: 'Garantie',
			body: [
				'Elk kader heeft de wettelijke conformiteitsgarantie van twee jaar vanaf de levering. Is het kader defect, neem dan zo snel mogelijk contact op en uiterlijk twee maanden nadat je het gebrek vastgesteld hebt. We herstellen of vervangen het, of betalen je terug als dat niet kan. Schade door verkeerd gebruik, vallen of water valt niet onder de garantie.'
			]
		},
		{
			id: 'liability',
			title: 'Aansprakelijkheid',
			body: [
				'We zijn aansprakelijk voor schade door onze fout zoals de wet bepaalt. Voor onrechtstreekse schade, zoals verlies van gegevens, zijn we niet aansprakelijk voor zover de wet dat toelaat. Niets in deze voorwaarden beperkt je rechten als consument.'
			]
		},
		{
			id: 'complaints',
			title: 'Vragen, klachten en geschillen',
			body: [
				'Met vragen of klachten kun je terecht bij jeroen@appmire.be; we antwoorden binnen twee werkdagen. Raken we er samen niet uit, dan kun je contact opnemen met de Consumentenombudsdienst via https://consumentenombudsdienst.be.',
				'Het Belgisch recht is van toepassing. Geschillen gaan naar de bevoegde Belgische rechtbanken, zonder dat je de bescherming verliest van het recht van het land waar je woont.'
			]
		},
		{
			id: 'privacy',
			title: 'Je persoonsgegevens',
			body: [
				'We gebruiken je gegevens alleen om je bestelling te leveren en af te handelen, en bewaren ze zolang de boekhoudwetgeving dat vraagt. Meer daarover in ons privacybeleid op https://kinframe.appmire.be/privacy.'
			]
		}
	]
};

export default doc;
