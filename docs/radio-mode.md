# Radiodrift: første testversjon

Appen kobler til den lagrede serveren automatisk etter fullført oppsett. Nettverksbrudd håndteres av den eksisterende gjenoppkoblingen. Hovedskjermen har ingen knapp som kobler fra. Tilbake åpner menyen; «Avslutt radioen» krever bekreftelse og stopper både forbindelsen og gjenoppkoblingen.

Forbindelsen eies av RadioSession og beskyttes av MumbleForegroundService. Et skjermbilde som ødelegges eller opprettes på nytt, kobler seg til samme sesjon. Gamle klienters callback-hendelser filtreres bort med en generasjonsteller. Mikrofon/PTT stoppes ved fokus- eller aktivitetstap, som før.

## Autostart og primærapp

Oppsett → Radiodrift, autostart og skjermlås tilbyr autostart og valg av OktoDrive som Android-startskjerm (HOME). Android må bekrefte valget. På Android 7–9 forsøker BOOT_COMPLETED-mottakeren også å åpne appen når autostart er slått på og oppsett er fullført. På nyere Android brukes HOME-valget fremfor å omgå restriksjoner på bakgrunnsstart. Eventuell PIN-lås hos Android må fortsatt åpnes.

Å slå av autostart opphever ikke Androids valg av startskjerm. Velg en annen startskjerm i Android for å oppheve HOME-valget. En avsluttet radio som også er startskjerm viser et stoppet skjermbilde med tilgang til Android-innstillinger og valg av annen startskjerm. En vanlig appstart starter radioen igjen. Dette er ikke administrert kioskmodus; systemets Home/oversikt og tvangsstopp beholdes.

## Håndholdt / B8000

Velg enhetsprofilen «Håndholdt / B8000 – lås etter 10 sek». Den samme APK-en brukes for håndholdt og fastmontert radio. B8000 har ingen antatte knappekoder; bruk knappelæringen på den konkrete enheten.

Berøringslåsen aktiveres etter ti sekunder uten skjermberøring, suspenderes under oppsett og menyer, demper lyset og blokkerer berøring av appkontroller. Den låser ikke fysisk PTT eller navigasjonsknapper. Skjermen holdes på under berøringslåsen. Power av/på åpner berøringen igjen; alternativt hold fingeren i ro på låseflaten i tre sekunder. Deretter gjelder en ny ti-sekunders periode.

Dette er en berøringslås, ikke Androids sikkerhetslås og ikke automatisk fysisk skjermavslåing. Mottaksforbindelsen kan leve videre med skjermen av, men fysisk PTT-levering ved helt avslått skjerm er avhengig av firmware og er ikke verifisert på B8000 eller TM-7Plus. Det er ikke lagt inn en tilgjengelighetstjeneste eller gjetninger om leverandørens bakgrunns-PTT.

Serverpassord lagres fortsatt ikke på disk. Det beholdes ved skjermbildereoppretting i samme prosess. Ved full prosessomstart må passordbeskyttede servere få passordet på nytt.

## Verifikasjon

Automatiske tester dekker terskelen ved ti sekunder, berøring som nullstiller tiden, menyer/oppsett som utsetter låsen, ignorert berøring under lås, power av/på, deaktivering og vilkår for automatisk start. Eksisterende tester av PTT-hold gjennom 201 repetisjoner og målte TM-7Plus-koder beholdes.

På fysisk enhet må følgende prøves før bruk som fast radio: kaldstart og HOME-valg; nettverksbrudd/gjenoppkobling; fysisk PTT under berøringslås; slipp som stopper sending; power av/på; mottakslyd med skjermen av; separat fysisk PTT med skjermen av; bevisst avslutning uten automatisk gjenoppkobling; start fra ikon etter avslutning; Android-innstillinger og retur fra HOME. Enhets- og lydtest er ikke gjennomført av den automatiske byggingen.
