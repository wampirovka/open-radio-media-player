# Open Radio – webová verze

Statická webová verze Android projektu **Open Radio & Media Player**. Obsahuje pouze internetová rádia, bez lokálního přehrávače médií.

## Funkce

- výchozí Fajn Rock Music
- česká rádia přes Radio Browser
- vyhledávání stanic
- oblíbené stanice v LocalStorage
- přidání vlastního streamu
- mini přehrávač a velký přehrávač
- automatický pokus o znovupřipojení při výpadku
- Media Session API tam, kde ho prohlížeč podporuje
- responzivní mobil / desktop

## Nasazení

Složka je čisté HTML/CSS/JS bez buildu. Nahraj obsah složky `web/` do kořene webhostingu, případně ji nastav jako publish directory na statickém hostingu.

> Poznámka: prohlížeče blokují automatické spuštění zvuku bez interakce uživatele. Proto web obnoví rozhraní, ale nezačne sám přehrávat rádio po otevření stránky.
