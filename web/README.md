# Open Radio – webová verze

Statická webová verze Android projektu **Open Radio & Media Player**. Obsahuje pouze internetová rádia, bez lokálního přehrávače médií.

## Funkce

- výchozí Fajn Rock Music a Rocková zábava přes HTTPS
- česká HTTPS rádia přes Radio Browser (HTTP streamy se na HTTPS stránce záměrně nenabízejí)
- vyhledávání stanic
- oblíbené stanice v LocalStorage
- přidání vlastního streamu
- mini přehrávač a velký přehrávač
- bezpečný, omezený reconnect při výpadku internetu; chyba jedné stanice neovlivní ostatní
- nativní buffer prohlížeče s nastavitelnou dobou čekání před reconnectem
- Media Session API tam, kde ho prohlížeč podporuje
- responzivní mobil / desktop

## Nasazení

Složka je čisté HTML/CSS/JS bez buildu. Nahraj obsah složky `web/` do kořene webhostingu, případně ji nastav jako publish directory na statickém hostingu.

> Poznámka: prohlížeče blokují automatické spuštění zvuku bez interakce uživatele. Proto web obnoví rozhraní, ale nezačne sám přehrávat rádio po otevření stránky.

## Streamovací vrstva

Přehrávač používá přímo HTTPS adresu každé stanice. Soubor stream.php zde úmyslně není: PHP relay dlouhých audio streamů je na běžném hostingu nespolehlivý a nemá být univerzální obchvat mixed contentu. Soubor metadata.php je samostatný nepovinný doplněk; pokud nevrátí ICY metadata, audio dál hraje.
