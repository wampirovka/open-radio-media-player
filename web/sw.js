const CACHE_NAME='open-radio-v8';
const APP_SHELL=['./','./index.html','./styles.css','./app.js','./manifest.webmanifest','./icons/sukadio-logo.svg'];

self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE_NAME).then(cache=>cache.addAll(APP_SHELL)).then(()=>self.skipWaiting()));
});

self.addEventListener('activate',event=>{
  event.waitUntil(
    caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE_NAME).map(k=>caches.delete(k)))).then(()=>self.clients.claim())
  );
});

self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url);
  if(url.origin!==self.location.origin || event.request.method!=='GET')return;
  const isShellRequest=event.request.mode==='navigate'||['script','style','image','manifest'].includes(event.request.destination);
  if(!isShellRequest)return;
  const fetchAndCache=()=>fetch(event.request).then(response=>{
    if(response.ok){
      const copy=response.clone();
      event.waitUntil(caches.open(CACHE_NAME).then(cache=>cache.put(event.request,copy)));
    }
    return response;
  });
  // Aplikace jde vždy nejdřív na síť; cache je výhradně offline záloha.
  // API a metadata se nikdy neukládají, aby nemohla vzniknout falešně aktuální data.
  event.respondWith(fetchAndCache().catch(()=>caches.match(event.request)));
});
