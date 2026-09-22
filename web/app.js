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
  fullArtwork:document.querySelector('#fullArtwork'), fullPlay:document.querySelector('#fullPlayPause'),
  addDialog:document.querySelector('#addDialog'), reconnect:document.querySelector('#reconnectToggle')
};

let stations=[];
let current=null;
let reconnectAttempt=0;
let reconnectTimer=null;
const customStations=loadJson('openradio.custom',[]);
const favoriteIds=new Set(loadJson('openradio.favorites',[]));
els.reconnect.checked=localStorage.getItem('openradio.reconnect')!=='false';

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
  clearTimeout(reconnectTimer); reconnectAttempt=0; current=station;
  els.audio.src=station.streamUrl; els.audio.load(); updatePlayer('Připojování…');
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