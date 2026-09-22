const API='https://de1.api.radio-browser.info/json/stations/search';
const DEFAULT_STATION={id:'fajn-rock-music',name:'Fajn Rock Music',streamUrl:'https://icecast1.play.cz/fajnrock128.mp3?1397678024622.mp3&r=375',homepageUrl:'https://www.fajnrockmusic.cz/',logoUrl:null,votes:999999,listeners:0,custom:true};

const els={
  audio:document.querySelector('#audio'), list:document.querySelector('#stationList'), status:document.querySelector('#status'),
  search:document.querySelector('#searchInput'), favoritesSection:document.querySelector('#favoritesSection'),
  favoritesRail:document.querySelector('#favoritesRail'), mini:document.querySelector('#miniPlayer'),
  miniTitle:document.querySelector('#miniTitle'), miniState:document.querySelector('#miniState'), miniLogo:document.querySelector('#miniLogo'),
  miniPlay:document.querySelector('#miniPlayPause'), miniStop:document.querySelector('#miniStop'),
  playerDialog:document.querySelector('#playerDialog'), fullTitle:document.querySelector('#fullTitle'),
  fullSubtitle:document.querySelector('#fullSubtitle'), fullState:document.querySelector('#fullState'),
  fullArtwork:document.querySelector('#fullArtwork'), fullPlay:document.querySelector('#fullPlayPause'), trackInfo:document.querySelector('#trackInfo'),
  visualizer:document.querySelector('#visualizer'), addDialog:document.querySelector('#addDialog'), reconnect:document.querySelector('#reconnectToggle')
};

let stations=[];
let current=null;
let reconnectAttempt=0;
let reconnectTimer=null;
let audioContext=null,analyser=null,sourceNode=null,visualizerFrame=null,metadataTimer=null;
const customStations=loadJson('openradio.custom',[]);
const favoriteIds=new Set(loadJson('openradio.favorites',[]));
els.reconnect.checked=localStorage.getItem('openradio.reconnect')!=='false';

function initVisualizer(){if(analyser)return true;try{audioContext=new(window.AudioContext||window.webkitAudioContext)();sourceNode=audioContext.createMediaElementSource(els.audio);analyser=audioContext.createAnalyser();analyser.fftSize=128;analyser.smoothingTimeConstant=.82;sourceNode.connect(analyser);analyser.connect(audioContext.destination);drawVisualizer();return true}catch(e){drawVisualizer();return false}}
function drawVisualizer(){if(!els.visualizer)return;const c=els.visualizer,ctx=c.getContext('2d');const resize=()=>{const r=c.getBoundingClientRect(),d=window.devicePixelRatio||1;c.width=r.width*d;c.height=r.height*d;ctx.setTransform(d,0,0,d,0,0)};resize();window.addEventListener('resize',resize,{passive:true});const loop=()=>{const r=c.getBoundingClientRect(),w=r.width,h=r.height;ctx.clearRect(0,0,w,h);if(analyser){const a=new Uint8Array(analyser.frequencyBinCount);analyser.getByteFrequencyData(a);const bars=34,gap=3,bw=(w-(bars-1)*gap)/bars;for(let i=0;i<bars;i++){const v=(a[Math.floor(i*a.length/bars)]||0)/255,bh=Math.max(4,v*h*.86),x=i*(bw+gap),y=h-bh,g=ctx.createLinearGradient(0,y,0,h);g.addColorStop(0,'#b7a5ff');g.addColorStop(.55,'#9ee7ff');g.addColorStop(1,'rgba(158,231,255,.12)');ctx.fillStyle=g;ctx.fillRect(x,y,bw,bh)}}else{ctx.fillStyle='rgba(158,231,255,.18)';for(let i=0;i<34;i++){const bh=4+Math.abs(Math.sin(Date.now()/450+i*.8))*18;ctx.fillRect(i*7,h-bh,4,bh)}}visualizerFrame=requestAnimationFrame(loop)};if(!visualizerFrame)loop()}
function updateTrack(title,artist){const t=(title||'').trim(),a=(artist||'').trim();els.trackInfo.textContent=t||a?[t,a].filter(Boolean).join(' • '):'Skladba a interpret nejsou ze streamu dostupné';if((t||a)&&'mediaSession'in navigator&&current)navigator.mediaSession.metadata=new MediaMetadata({title:t||current.name,artist:a||'Internet Radio',album:current.name,artwork:current.logoUrl?[{src:current.logoUrl}]:[]})}
function parseStreamTitle(raw){const value=(raw||'').trim();if(!value)return {title:'',artist:''};const clean=value.replace(/\\s+/g,' ').trim();let p=clean.split(' - ');if(p.length>1){const artist=p.shift().trim(),title=p.join(' - ').trim();return {title,artist}}return {title:clean,artist:''}}
async function readMetadataDirect(url){const r=await fetch(url,{cache:'no-store',headers:{'Icy-MetaData':'1'}});const n=Number(r.headers.get('icy-metaint'));if(!r.ok||!n||!r.body)throw new Error('ICY metadata není dostupná');const reader=r.body.getReader();let buf=new Uint8Array(0);for(let i=0;i<12;i++){const part=await reader.read();if(part.done)break;if(part.value){const next=new Uint8Array(buf.length+part.value.length);next.set(buf);next.set(part.value,buf.length);buf=next}if(buf.length>=n+1){const len=buf[n]*16;if(buf.length>=n+1+len){const s=new TextDecoder('iso-8859-1').decode(buf.slice(n+1,n+1+len));const m=s.match(/StreamTitle='(.*?)';/);if(m)return parseStreamTitle(m[1])}}}throw new Error('ICY metadata se nepodařilo přečíst')}
async function readMetadata(url){try{return await readMetadataDirect(url)}catch{const r=await fetch('metadata.php?url='+encodeURIComponent(url),{cache:'no-store'});if(!r.ok)throw new Error('Metadata proxy selhala');return await r.json()}}
async function pollStreamMetadata(){clearInterval(metadataTimer);if(!current)return;const read=async()=>{try{const meta=await readMetadata(current.streamUrl);if(meta?.title||meta?.artist)updateTrack(meta.title,meta.artist)}catch{}};read();metadataTimer=setInterval(read,15000)}
function loadJson(key,fallback){try{return JSON.parse(localStorage.getItem(key))??fallback}catch{return fallback}}
function saveJson(key,val){localStorage.setItem(key,JSON.stringify(val))}
function initials(name){return name.trim().split(/\s+/).slice(0,2).map(x=>x[0]?.toUpperCase()||'').join('')||'R'}
function stationLogo(station,cls='logo'){
  const div=document.createElement('div'); div.className=cls;
  if(station.logoUrl){
    const img=new Image(); img.alt=''; img.src=station.logoUrl;
    img.onerror=()=>{div.textContent=initials(station.name)};
    div.append(img);
  }else div.textContent=initials(station.name);
  return div;
}
function normalize(item){
  const url=item.url_resolved||item.url;
  if(!item.stationuuid||!item.name||!/^https?:\/\//.test(url||''))return null;
  return {id:item.stationuuid,name:item.name.trim(),streamUrl:url,homepageUrl:item.homepage||null,logoUrl:item.favicon||null,votes:item.votes||0,listeners:item.clickcount||0};
}
function merged(apiStations){
  const map=new Map();
  [DEFAULT_STATION,...customStations,...apiStations].forEach(s=>map.set(s.id,s));
  return [...map.values()];
}
async function loadStations(query=''){
  els.status.textContent='Načítám stanice…';
  try{
    const params=new URLSearchParams({hidebroken:'true',order:'votes',reverse:'true',limit:'50'});
    if(query.trim())params.set('name',query.trim()); else params.set('countrycode','CZ');
    const res=await fetch(API+'?'+params,{headers:{Accept:'application/json'}});
    if(!res.ok)throw new Error('HTTP '+res.status);
    const data=await res.json();
    const api=data.map(normalize).filter(Boolean);
    stations=merged(api);
    render();
    els.status.textContent=stations.length+' stanic';
  }catch(err){
    stations=merged([]);
    render();
    els.status.textContent='Nepodařilo se aktualizovat stanice. Zobrazuji uložená a výchozí rádia.';
  }
}
function render(){
  renderStations(); renderFavorites();
}
function renderStations(){
  els.list.innerHTML='';
  stations.forEach(station=>{
    const row=document.createElement('article');
    row.className='station-row'+(current?.id===station.id?' active':'');
    row.append(stationLogo(station));
    const meta=document.createElement('div'); meta.className='station-meta';
    const title=document.createElement('strong'); title.textContent=station.name;
    const sub=document.createElement('small'); sub.textContent=current?.id===station.id?(els.audio.paused?'Pozastaveno':'▶ Hraje'):(station.custom?'Vlastní / výchozí stanice':'Internet Radio');
    meta.append(title,sub);
    const fav=document.createElement('button'); fav.className='fav-btn'+(favoriteIds.has(station.id)?' on':''); fav.type='button'; fav.textContent=favoriteIds.has(station.id)?'♥':'♡'; fav.title='Oblíbené';
    fav.addEventListener('click',e=>{e.stopPropagation();toggleFavorite(station)});
    row.append(meta,fav);
    row.addEventListener('click',()=>playStation(station));
    els.list.append(row);
  });
}
function renderFavorites(){
  const all=merged(stations);
  const favs=all.filter(s=>favoriteIds.has(s.id));
  els.favoritesSection.classList.toggle('hidden',favs.length===0);
  els.favoritesRail.innerHTML='';
  favs.forEach(station=>{
    const card=document.createElement('button'); card.className='favorite-card'; card.type='button';
    card.append(stationLogo(station,'favorite-logo'));
    const title=document.createElement('strong'); title.textContent=station.name;
    const sub=document.createElement('small'); sub.textContent=current?.id===station.id&&!els.audio.paused?'▶ Hraje':'▶ Přehrát';
    card.append(title,sub); card.addEventListener('click',()=>playStation(station)); els.favoritesRail.append(card);
  });
}
function toggleFavorite(station){
  favoriteIds.has(station.id)?favoriteIds.delete(station.id):favoriteIds.add(station.id);
  saveJson('openradio.favorites',[...favoriteIds]); render();
}
async function playStation(station){
  clearTimeout(reconnectTimer); clearInterval(metadataTimer); reconnectAttempt=0; current=station; updateTrack('',''); initVisualizer(); if(audioContext?.state==='suspended')audioContext.resume().catch(()=>{});
  els.audio.src=station.streamUrl; els.audio.load(); updatePlayer('Připojování…'); pollStreamMetadata();
  try{await els.audio.play()}catch(err){updatePlayer('Klikni na ▶ pro spuštění')}
  render();
  if('mediaSession'in navigator){
    navigator.mediaSession.metadata=new MediaMetadata({title:station.name,artist:'Internet Radio',artwork:station.logoUrl?[{src:station.logoUrl}]:[]});
  }
}
function updatePlayer(state){
  if(!current){els.mini.classList.add('hidden');return}
  els.mini.classList.remove('hidden');
  els.miniTitle.textContent=current.name; els.miniState.textContent=state;
  els.fullTitle.textContent=current.name; els.fullSubtitle.textContent='Internet Radio'; els.fullState.textContent=state;
  const symbol=els.audio.paused?'▶':'❚❚'; els.miniPlay.textContent=symbol; els.fullPlay.textContent=symbol;
  setArtwork(els.miniLogo,current,'mini-logo'); setArtwork(els.fullArtwork,current,'full-artwork');
}
function setArtwork(el,station,cls){
  el.className=cls; el.innerHTML='';
  if(station.logoUrl){const img=new Image();img.alt='';img.src=station.logoUrl;img.onerror=()=>{el.textContent=initials(station.name)};el.append(img)}
  else el.textContent=initials(station.name);
}
async function togglePlay(){
  if(!current)return;
  if(els.audio.paused){try{await els.audio.play()}catch{}}
  else els.audio.pause();
}
function stop(){clearTimeout(reconnectTimer); els.audio.pause(); els.audio.removeAttribute('src'); els.audio.load(); updatePlayer('Zastaveno');render()}
function scheduleReconnect(){
  if(!current||!els.reconnect.checked)return;
  clearTimeout(reconnectTimer);
  const delays=[10000,20000,30000,60000],delay=delays[Math.min(reconnectAttempt,3)]; reconnectAttempt++;
  updatePlayer('Výpadek, nový pokus…');
  reconnectTimer=setTimeout(()=>{if(current){els.audio.src=current.streamUrl;els.audio.load();els.audio.play().catch(()=>{})}},delay);
}
els.audio.addEventListener('playing',()=>{reconnectAttempt=0;updatePlayer('Hraje');render()});
els.audio.addEventListener('pause',()=>{if(current&&els.audio.src)updatePlayer('Pozastaveno');render()});
els.audio.addEventListener('waiting',()=>updatePlayer('Připojování…'));
els.audio.addEventListener('error',scheduleReconnect);
els.miniPlay.addEventListener('click',togglePlay);els.fullPlay.addEventListener('click',togglePlay);
els.miniStop.addEventListener('click',stop);document.querySelector('#fullStop').addEventListener('click',stop);
document.querySelector('#miniOpen').addEventListener('click',()=>els.playerDialog.showModal());
document.querySelector('#closePlayer').addEventListener('click',()=>els.playerDialog.close());
document.querySelector('#searchBtn').addEventListener('click',()=>loadStations(els.search.value));
document.querySelector('#czechBtn').addEventListener('click',()=>{els.search.value='';loadStations('')});
document.querySelector('#refreshBtn').addEventListener('click',()=>loadStations(els.search.value));
els.search.addEventListener('keydown',e=>{if(e.key==='Enter')loadStations(els.search.value)});
document.querySelector('#addStationBtn').addEventListener('click',()=>els.addDialog.showModal());
document.querySelector('#addForm').addEventListener('submit',e=>{
  const submitter=e.submitter;if(submitter?.value==='cancel')return;
  e.preventDefault();
  const name=document.querySelector('#customName').value.trim();
  const streamUrl=document.querySelector('#customUrl').value.trim();
  const logoUrl=document.querySelector('#customLogo').value.trim()||null;
  if(!name||!/^https?:\/\//.test(streamUrl))return;
  const id='custom:'+btoa(unescape(encodeURIComponent(streamUrl))).replace(/=+$/,'').slice(-32);
  const station={id,name,streamUrl,logoUrl,homepageUrl:null,votes:0,listeners:0,custom:true};
  const idx=customStations.findIndex(s=>s.id===id);if(idx>=0)customStations[idx]=station;else customStations.unshift(station);
  saveJson('openradio.custom',customStations);els.addDialog.close();e.target.reset();stations=merged(stations);render();
});
els.reconnect.addEventListener('change',()=>localStorage.setItem('openradio.reconnect',String(els.reconnect.checked)));
document.querySelectorAll('.nav-item').forEach(btn=>btn.addEventListener('click',()=>{
  document.querySelectorAll('.nav-item').forEach(x=>x.classList.remove('active'));btn.classList.add('active');
  const target=btn.dataset.scroll;
  if(target==='top')window.scrollTo({top:0,behavior:'smooth'});
  else if(target==='favorites')document.querySelector('#favoritesSection').scrollIntoView({behavior:'smooth'});
  else document.querySelector('.settings').scrollIntoView({behavior:'smooth'});
}));
if('mediaSession'in navigator){
  navigator.mediaSession.setActionHandler('play',()=>els.audio.play().catch(()=>{}));
  navigator.mediaSession.setActionHandler('pause',()=>els.audio.pause());
  navigator.mediaSession.setActionHandler('stop',stop);
}
loadStations();