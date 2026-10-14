const RADIO_BROWSER_API = 'https://de1.api.radio-browser.info/json/stations/search';
const MAX_RECONNECT_ATTEMPTS = 4;
const RECONNECT_DELAYS = [3000, 7000, 15000, 30000];

// Stations are data. There are no station-specific playback hacks or audio proxies.
const BUILT_IN_STATIONS = [
  { id: 'fajn-rock-music', name: 'Fajn Rock Music', streamUrl: 'https://icecast1.play.cz/fajnrock128.mp3?1397678024622.mp3&r=375', homepageUrl: 'https://www.fajnrockmusic.cz/', logoUrl: null, votes: 999999, listeners: 0, builtIn: true },
  { id: 'rockova-zabava', name: 'Rocková zábava', streamUrl: 'https://ice2.radia.cz/rockzabava128.aac', homepageUrl: null, logoUrl: null, votes: 999998, listeners: 0, builtIn: true }
];

const els = {
  audio: document.querySelector('#audio'), list: document.querySelector('#stationList'), status: document.querySelector('#status'),
  search: document.querySelector('#searchInput'), favoritesSection: document.querySelector('#favoritesSection'), favoritesRail: document.querySelector('#favoritesRail'),
  mini: document.querySelector('#miniPlayer'), miniTitle: document.querySelector('#miniTitle'), miniState: document.querySelector('#miniState'), miniLogo: document.querySelector('#miniLogo'),
  miniPlay: document.querySelector('#miniPlayPause'), miniStop: document.querySelector('#miniStop'), playerDialog: document.querySelector('#playerDialog'),
  fullTitle: document.querySelector('#fullTitle'), fullSubtitle: document.querySelector('#fullSubtitle'), fullState: document.querySelector('#fullState'),
  fullArtwork: document.querySelector('#fullArtwork'), fullPlay: document.querySelector('#fullPlayPause'), trackInfo: document.querySelector('#trackInfo'),
  nowTitle: document.querySelector('#nowPlayingTitle'), nowArtist: document.querySelector('#nowPlayingArtist'), visualizer: document.querySelector('#visualizer'),
  addDialog: document.querySelector('#addDialog'), reconnect: document.querySelector('#reconnectToggle'),
  bufferMode: document.querySelector('#bufferMode'), bufferRange: document.querySelector('#bufferRange'), bufferValue: document.querySelector('#bufferValue'), bufferCustom: document.querySelector('#bufferCustom')
};

const playback = {
  session: 0, current: null, reconnectAttempt: 0, reconnectTimer: null, stallTimer: null, bufferTimer: null,
  metadataTimer: null, metadataRequest: null, metadataLoading: false, interruptionTimer: null,
  userPaused: false, intendedPlaying: false, waiting: false, adaptiveGrace: 12
};

let stations = [];
let currentTrack = { title: '', artist: '' };
let audioContext = null;
let analyser = null;
let visualizerFrame = null;
const metadataUnavailable = new Set();
const customStations = loadJson('openradio.custom', []);
const favoriteIds = new Set(loadJson('openradio.favorites', []));
const BUFFER_PRESETS = { low: 5, normal: 12, stable: 25, max: 45 };

els.reconnect.checked = localStorage.getItem('openradio.reconnect') !== 'false';
const savedBufferMode = localStorage.getItem('openradio.bufferMode') || 'auto';
const savedBufferRange = clamp(Number(localStorage.getItem('openradio.bufferRange') || 20), 5, 60);
if (els.bufferMode) els.bufferMode.value = savedBufferMode;
if (els.bufferRange) els.bufferRange.value = String(savedBufferRange);

function clamp(value, minimum, maximum) { return Math.min(maximum, Math.max(minimum, value)); }
function loadJson(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function saveJson(key, value) { localStorage.setItem(key, JSON.stringify(value)); }
function initials(name) { return name.trim().split(/\s+/).slice(0, 2).map(word => word[0]?.toUpperCase() || '').join('') || 'R'; }
function isSecurePage() { return location.protocol === 'https:'; }

function isPlayableStream(url) {
  try {
    const parsed = new URL(url, location.href);
    return ['http:', 'https:'].includes(parsed.protocol) && (!isSecurePage() || parsed.protocol === 'https:');
  } catch {
    return false;
  }
}

function normalizeStation(item) {
  const streamUrl = item.url_resolved || item.url;
  if (!item.stationuuid || !item.name || !isPlayableStream(streamUrl)) return null;
  return { id: item.stationuuid, name: item.name.trim(), streamUrl, homepageUrl: item.homepage || null, logoUrl: item.favicon || null, votes: item.votes || 0, listeners: item.clickcount || 0 };
}

function availableStations(apiStations = []) {
  const byId = new Map();
  [...BUILT_IN_STATIONS, ...customStations, ...apiStations].filter(station => isPlayableStream(station.streamUrl)).forEach(station => byId.set(station.id, station));
  return [...byId.values()];
}

function getBufferGraceSeconds() {
  if (!els.bufferMode) return 12;
  const mode = els.bufferMode.value;
  if (mode === 'custom') return Number(els.bufferRange?.value || 20);
  if (mode === 'auto') return playback.adaptiveGrace;
  return BUFFER_PRESETS[mode] || 12;
}

function bufferedSeconds() {
  try {
    const ranges = els.audio.buffered;
    const time = els.audio.currentTime;
    for (let index = 0; index < ranges.length; index += 1) {
      if (time >= ranges.start(index) - 0.25 && time <= ranges.end(index) + 0.25) return Math.max(0, ranges.end(index) - time);
    }
  } catch {}
  return 0;
}

function configureBufferUi() {
  if (!els.bufferMode) return;
  const custom = els.bufferMode.value === 'custom';
  els.bufferCustom?.classList.toggle('hidden', !custom);
  if (els.bufferRange) els.bufferRange.disabled = !custom;
  const value = custom ? els.bufferRange.value + ' s' : els.bufferMode.options[els.bufferMode.selectedIndex]?.text || 'Automaticky';
  if (els.bufferValue) els.bufferValue.textContent = value;
}

function clearTimer(name) {
  if (playback[name]) clearTimeout(playback[name]);
  playback[name] = null;
}

function stopBufferMonitor() {
  clearInterval(playback.bufferTimer);
  playback.bufferTimer = null;
}

function startBufferMonitor() {
  stopBufferMonitor();
  playback.bufferTimer = setInterval(() => {
    if (!playback.current || els.audio.paused) return;
    // Diagnostic only: browser media buffering stays in charge. We never pause
    // a live stream merely to try to manufacture a larger buffer.
    const seconds = bufferedSeconds();
    if (seconds > 0 && els.fullState?.textContent === 'Hraje') els.fullState.textContent = 'Hraje • rezerva ' + Math.round(seconds) + ' s';
  }, 1500);
}

function clearStallWatch() {
  clearTimer('stallTimer');
  playback.waiting = false;
}

function startStallWatch() {
  clearStallWatch();
  if (!playback.current || playback.userPaused || !navigator.onLine) return;
  playback.waiting = true;
  const graceMs = Math.min(getBufferGraceSeconds(), 45) * 1000;
  playback.stallTimer = setTimeout(() => {
    playback.stallTimer = null;
    if (playback.waiting && !els.audio.paused) scheduleReconnect('Připojení se neobnovilo');
  }, graceMs);
}

function stopMetadataPolling() {
  clearInterval(playback.metadataTimer);
  playback.metadataTimer = null;
  playback.metadataRequest?.abort();
  playback.metadataRequest = null;
  playback.metadataLoading = false;
}

async function requestMetadata(url) {
  const controller = new AbortController();
  playback.metadataRequest = controller;
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('metadata.php?url=' + encodeURIComponent(url), { cache: 'no-store', signal: controller.signal });
    if (response.status === 204) return null;
    if (!response.ok) throw new Error('Metadata HTTP ' + response.status);
    return response.json();
  } finally {
    clearTimeout(timeout);
    if (playback.metadataRequest === controller) playback.metadataRequest = null;
  }
}

function startMetadataPolling() {
  stopMetadataPolling();
  const station = playback.current;
  if (!station || metadataUnavailable.has(station.streamUrl)) return;
  const session = playback.session;
  const read = async () => {
    if (playback.metadataLoading || playback.session !== session || playback.current?.streamUrl !== station.streamUrl) return;
    playback.metadataLoading = true;
    try {
      const metadata = await requestMetadata(station.streamUrl);
      if (playback.session === session && (metadata?.title || metadata?.artist)) updateTrack(metadata.title, metadata.artist);
      if (!metadata) metadataUnavailable.add(station.streamUrl);
    } catch {
      // Metadata are optional. A broken endpoint must never influence audio.
      metadataUnavailable.add(station.streamUrl);
    } finally {
      playback.metadataLoading = false;
      if (metadataUnavailable.has(station.streamUrl)) stopMetadataPolling();
    }
  };
  read();
  playback.metadataTimer = setInterval(read, 20000);
}

function initVisualizer() {
  if (analyser || visualizerFrame) return;
  try {
    audioContext = new (window.AudioContext || window.webkitAudioContext)();
    const source = audioContext.createMediaElementSource(els.audio);
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 128;
    analyser.smoothingTimeConstant = 0.82;
    source.connect(analyser);
    analyser.connect(audioContext.destination);
  } catch {
    // Some hosts prohibit analyser data. Audio remains playable and the
    // visualizer below renders a neutral fallback.
    analyser = null;
  }
  drawVisualizer();
}

function drawVisualizer() {
  if (!els.visualizer || visualizerFrame) return;
  const canvas = els.visualizer;
  const context = canvas.getContext('2d');
  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    const scale = window.devicePixelRatio || 1;
    canvas.width = rect.width * scale;
    canvas.height = rect.height * scale;
    context.setTransform(scale, 0, 0, scale, 0, 0);
  };
  resize();
  window.addEventListener('resize', resize, { passive: true });
  const loop = () => {
    const rect = canvas.getBoundingClientRect();
    const width = rect.width;
    const height = rect.height;
    context.clearRect(0, 0, width, height);
    const bars = 34;
    const gap = 3;
    const barWidth = (width - (bars - 1) * gap) / bars;
    const data = analyser ? new Uint8Array(analyser.frequencyBinCount) : null;
    if (data) analyser.getByteFrequencyData(data);
    for (let index = 0; index < bars; index += 1) {
      const level = data ? (data[Math.floor(index * data.length / bars)] || 0) / 255 : Math.abs(Math.sin(Date.now() / 450 + index * 0.8)) * 0.24;
      const barHeight = Math.max(4, level * height * 0.86);
      const y = height - barHeight;
      const gradient = context.createLinearGradient(0, y, 0, height);
      gradient.addColorStop(0, '#ffffff');
      gradient.addColorStop(0.55, '#F8AD0E');
      gradient.addColorStop(1, 'rgba(248,173,14,.10)');
      context.fillStyle = gradient;
      context.fillRect(index * (barWidth + gap), y, barWidth, barHeight);
    }
    visualizerFrame = requestAnimationFrame(loop);
  };
  loop();
}

function updateTrack(title, artist) {
  const cleanTitle = (title || '').trim();
  const cleanArtist = (artist || '').trim();
  currentTrack = { title: cleanTitle, artist: cleanArtist };
  const label = [cleanTitle, cleanArtist].filter(Boolean).join(' • ');
  els.trackInfo.textContent = label || 'Skladba a interpret nejsou ze streamu dostupné';
  els.nowTitle.textContent = cleanTitle || playback.current?.name || 'Sukadio → RockPower';
  els.nowArtist.textContent = cleanArtist || (cleanTitle ? 'Interpret není ve streamu uveden' : 'Vyber rádio pro spuštění');
  if (!playback.current) return;
  els.miniTitle.textContent = cleanTitle || playback.current.name;
  els.fullTitle.textContent = cleanTitle || playback.current.name;
  els.fullSubtitle.textContent = cleanArtist ? cleanArtist + ' • ' + playback.current.name : 'Internet Radio';
  if ((cleanTitle || cleanArtist) && 'mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: cleanTitle || playback.current.name, artist: cleanArtist || 'Internet Radio',
      album: playback.current.name, artwork: playback.current.logoUrl ? [{ src: playback.current.logoUrl }] : []
    });
  }
}

function setArtwork(element, station, className) {
  element.className = className;
  element.innerHTML = '';
  if (station.logoUrl) {
    const image = new Image();
    image.alt = '';
    image.src = station.logoUrl;
    image.onerror = () => { element.textContent = initials(station.name); };
    element.append(image);
  } else {
    element.textContent = initials(station.name);
  }
}

function stationLogo(station, className = 'logo') {
  const logo = document.createElement('div');
  logo.className = className;
  if (station.logoUrl) {
    const image = new Image();
    image.alt = '';
    image.src = station.logoUrl;
    image.onerror = () => { logo.textContent = initials(station.name); };
    logo.append(image);
  } else {
    logo.textContent = initials(station.name);
  }
  return logo;
}

function updatePlayer(state) {
  const station = playback.current;
  if (!station) {
    els.mini.classList.add('hidden');
    return;
  }
  els.mini.classList.remove('hidden');
  els.miniTitle.textContent = currentTrack.title || station.name;
  els.miniState.textContent = currentTrack.artist ? currentTrack.artist + ' • ' + state : state;
  els.fullTitle.textContent = currentTrack.title || station.name;
  els.fullSubtitle.textContent = currentTrack.artist ? currentTrack.artist + ' • ' + station.name : 'Internet Radio';
  els.fullState.textContent = state;
  const symbol = els.audio.paused ? '▶' : '❚❚';
  els.miniPlay.textContent = symbol;
  els.fullPlay.textContent = symbol;
  setArtwork(els.miniLogo, station, 'mini-logo');
  setArtwork(els.fullArtwork, station, 'full-artwork');
}

function renderFavorites() {
  const favorites = availableStations(stations).filter(station => favoriteIds.has(station.id));
  els.favoritesSection.classList.toggle('hidden', favorites.length === 0);
  els.favoritesRail.innerHTML = '';
  favorites.forEach(station => {
    const card = document.createElement('button');
    card.className = 'favorite-card';
    card.type = 'button';
    card.append(stationLogo(station, 'favorite-logo'));
    const name = document.createElement('strong');
    name.textContent = station.name;
    const action = document.createElement('small');
    action.textContent = playback.current?.id === station.id && !els.audio.paused ? '▶ Hraje' : '▶ Přehrát';
    card.append(name, action);
    card.addEventListener('click', () => playStation(station));
    els.favoritesRail.append(card);
  });
}

function render() {
  els.list.innerHTML = '';
  stations.forEach(station => {
    const row = document.createElement('article');
    row.className = 'station-row' + (playback.current?.id === station.id ? ' active' : '');
    row.append(stationLogo(station));
    const details = document.createElement('div');
    details.className = 'station-meta';
    const name = document.createElement('strong');
    name.textContent = station.name;
    const description = document.createElement('small');
    description.textContent = playback.current?.id === station.id ? (els.audio.paused ? 'Pozastaveno' : '▶ Hraje') : (station.builtIn ? 'Ověřená stanice' : 'Internet Radio');
    details.append(name, description);
    const favorite = document.createElement('button');
    favorite.className = 'fav-btn' + (favoriteIds.has(station.id) ? ' on' : '');
    favorite.type = 'button';
    favorite.textContent = favoriteIds.has(station.id) ? '♥' : '♡';
    favorite.title = 'Oblíbené';
    favorite.addEventListener('click', event => { event.stopPropagation(); toggleFavorite(station); });
    row.append(details, favorite);
    row.addEventListener('click', () => playStation(station));
    els.list.append(row);
  });
  renderFavorites();
}

function toggleFavorite(station) {
  if (favoriteIds.has(station.id)) favoriteIds.delete(station.id);
  else favoriteIds.add(station.id);
  saveJson('openradio.favorites', [...favoriteIds]);
  render();
}

async function loadStations(query = '') {
  els.status.textContent = 'Načítám stanice…';
  try {
    const params = new URLSearchParams({ hidebroken: 'true', is_https: 'true', order: 'votes', reverse: 'true', limit: '50' });
    if (query.trim()) params.set('name', query.trim());
    else params.set('countrycode', 'CZ');
    const response = await fetch(RADIO_BROWSER_API + '?' + params, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    stations = availableStations((await response.json()).map(normalizeStation).filter(Boolean));
    els.status.textContent = stations.length + ' HTTPS stanic';
  } catch {
    stations = availableStations();
    els.status.textContent = 'Seznam stanic se nepodařilo aktualizovat. Zobrazuji ověřené a vlastní stanice.';
  }
  render();
}

function clearPlaybackTimers() {
  clearTimer('reconnectTimer');
  clearTimer('interruptionTimer');
  clearStallWatch();
  stopBufferMonitor();
  stopMetadataPolling();
}

function setMediaPlaybackState(state) {
  if (!('mediaSession' in navigator)) return;
  try { navigator.mediaSession.playbackState = state; } catch {}
}

async function resumeAfterInterruption() {
  if (!playback.current || !playback.intendedPlaying || playback.userPaused || !els.audio.paused) return;
  try {
    await els.audio.play();
    setMediaPlaybackState('playing');
  } catch {
    scheduleReconnect('Přehrávání se po přerušení neobnovilo');
  }
}

function canReconnect() {
  return Boolean(playback.current && els.reconnect.checked && !playback.userPaused && navigator.onLine);
}

function scheduleReconnect(reason) {
  if (!canReconnect() || playback.reconnectTimer) return;
  clearStallWatch();
  stopBufferMonitor();
  if (playback.reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
    updatePlayer('Stanice není dostupná');
    render();
    return;
  }
  const delay = RECONNECT_DELAYS[playback.reconnectAttempt];
  playback.reconnectAttempt += 1;
  updatePlayer(reason + ' • nový pokus ' + playback.reconnectAttempt + '/' + MAX_RECONNECT_ATTEMPTS);
  playback.reconnectTimer = setTimeout(() => {
    playback.reconnectTimer = null;
    if (canReconnect()) connectCurrentStation();
  }, delay);
}

function connectCurrentStation() {
  const station = playback.current;
  if (!station || !isPlayableStream(station.streamUrl)) return;
  const session = playback.session;
  clearStallWatch();
  stopBufferMonitor();
  stopMetadataPolling();
  updatePlayer('Připojuji…');
  els.audio.src = station.streamUrl;
  els.audio.load();
  els.audio.play().catch(error => {
    if (playback.session !== session || playback.userPaused) return;
    if (error?.name === 'NotAllowedError') updatePlayer('Klikni na ▶ pro spuštění');
    else scheduleReconnect('Stream se nepodařilo spustit');
  });
}

function playStation(station) {
  if (!isPlayableStream(station.streamUrl)) {
    playback.current = station;
    updatePlayer('Tento stream není z HTTPS stránky bezpečně přehratelný');
    render();
    return;
  }
  playback.session += 1;
  clearPlaybackTimers();
  playback.current = station;
  playback.reconnectAttempt = 0;
  playback.userPaused = false;
  playback.intendedPlaying = true;
  playback.adaptiveGrace = 12;
  currentTrack = { title: '', artist: '' };
  updateTrack('', '');
  initVisualizer();
  if (audioContext?.state === 'suspended') audioContext.resume().catch(() => {});
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: station.name, artist: 'Internet Radio', artwork: station.logoUrl ? [{ src: station.logoUrl }] : []
    });
  }
  connectCurrentStation();
  render();
}

async function togglePlay() {
  if (!playback.current) return;
  if (els.audio.paused) {
    playback.userPaused = false;
    playback.intendedPlaying = true;
    clearTimer('interruptionTimer');
    updatePlayer('Připojuji…');
    try { await els.audio.play(); } catch (error) {
      if (error?.name === 'NotAllowedError') updatePlayer('Klikni na ▶ pro spuštění');
      else scheduleReconnect('Stream se nepodařilo spustit');
    }
  } else {
    playback.userPaused = true;
    playback.intendedPlaying = false;
    clearPlaybackTimers();
    els.audio.pause();
  }
}

function stop() {
  playback.session += 1;
  playback.userPaused = true;
  playback.intendedPlaying = false;
  playback.reconnectAttempt = 0;
  clearPlaybackTimers();
  els.audio.pause();
  els.audio.removeAttribute('src');
  els.audio.load();
  updatePlayer('Zastaveno');
  render();
}

els.audio.addEventListener('playing', () => {
  if (!playback.current) return;
  clearTimer('reconnectTimer');
  clearTimer('interruptionTimer');
  clearStallWatch();
  playback.reconnectAttempt = 0;
  playback.intendedPlaying = true;
  playback.adaptiveGrace = Math.max(12, playback.adaptiveGrace - 1);
  updatePlayer('Hraje');
  setMediaPlaybackState('playing');
  startBufferMonitor();
  startMetadataPolling();
  render();
});
els.audio.addEventListener('pause', () => {
  stopBufferMonitor();
  clearStallWatch();
  if (playback.current && els.audio.src && playback.userPaused) {
    setMediaPlaybackState('paused');
    updatePlayer('Pozastaveno');
    render();
  } else if (playback.current && els.audio.src && playback.intendedPlaying) {
    clearTimer('interruptionTimer');
    playback.interruptionTimer = setTimeout(resumeAfterInterruption, 1200);
  }
});
els.audio.addEventListener('waiting', () => {
  if (!playback.current || playback.userPaused) return;
  if (els.bufferMode?.value === 'auto') playback.adaptiveGrace = Math.min(30, playback.adaptiveGrace + 3);
  updatePlayer('Doplňuji data…');
  startStallWatch();
});
els.audio.addEventListener('stalled', () => {
  if (!playback.current || playback.userPaused) return;
  updatePlayer('Doplňuji data…');
  startStallWatch();
});
els.audio.addEventListener('error', () => scheduleReconnect('Stream se nepodařilo přehrát'));
els.audio.addEventListener('ended', () => scheduleReconnect('Spojení se ukončilo'));

window.addEventListener('offline', () => {
  clearTimer('reconnectTimer');
  clearStallWatch();
  if (playback.current && !playback.userPaused) updatePlayer('Jste offline');
});
window.addEventListener('online', () => {
  if (playback.current && !playback.userPaused) {
    playback.reconnectAttempt = 0;
    scheduleReconnect('Připojení je zpět');
  }
});

els.miniPlay.addEventListener('click', togglePlay);
els.fullPlay.addEventListener('click', togglePlay);
els.miniStop.addEventListener('click', stop);
document.querySelector('#fullStop').addEventListener('click', stop);
document.querySelector('#miniOpen').addEventListener('click', () => els.playerDialog.showModal());
document.querySelector('#closePlayer').addEventListener('click', () => els.playerDialog.close());
document.querySelector('#searchBtn').addEventListener('click', () => loadStations(els.search.value));
document.querySelector('#czechBtn').addEventListener('click', () => { els.search.value = ''; loadStations(''); });
document.querySelector('#refreshBtn').addEventListener('click', () => loadStations(els.search.value));
els.search.addEventListener('keydown', event => { if (event.key === 'Enter') loadStations(els.search.value); });
document.querySelector('#addStationBtn').addEventListener('click', () => els.addDialog.showModal());
document.querySelector('#addForm').addEventListener('submit', event => {
  const submitter = event.submitter;
  if (submitter?.value === 'cancel') return;
  event.preventDefault();
  const name = document.querySelector('#customName').value.trim();
  const streamUrl = document.querySelector('#customUrl').value.trim();
  const logoUrl = document.querySelector('#customLogo').value.trim() || null;
  if (!name || !isPlayableStream(streamUrl)) {
    const input = document.querySelector('#customUrl');
    input.setCustomValidity('Na HTTPS webu je možné přidat pouze HTTPS stream.');
    input.reportValidity();
    return;
  }
  const id = 'custom:' + btoa(unescape(encodeURIComponent(streamUrl))).replace(/=+$/, '').slice(-32);
  const station = { id, name, streamUrl, logoUrl, homepageUrl: null, votes: 0, listeners: 0, custom: true };
  const index = customStations.findIndex(item => item.id === id);
  if (index >= 0) customStations[index] = station;
  else customStations.unshift(station);
  saveJson('openradio.custom', customStations);
  els.addDialog.close();
  event.target.reset();
  stations = availableStations(stations);
  render();
});
document.querySelector('#customUrl').addEventListener('input', event => event.target.setCustomValidity(''));

els.reconnect.addEventListener('change', () => localStorage.setItem('openradio.reconnect', String(els.reconnect.checked)));
els.bufferMode?.addEventListener('change', () => {
  localStorage.setItem('openradio.bufferMode', els.bufferMode.value);
  configureBufferUi();
});
els.bufferRange?.addEventListener('input', () => {
  localStorage.setItem('openradio.bufferRange', String(els.bufferRange.value));
  configureBufferUi();
});
configureBufferUi();

document.querySelectorAll('.nav-item').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('.nav-item').forEach(item => item.classList.remove('active'));
  button.classList.add('active');
  const target = button.dataset.scroll;
  if (target === 'top') window.scrollTo({ top: 0, behavior: 'smooth' });
  else if (target === 'favorites') document.querySelector('#favoritesSection').scrollIntoView({ behavior: 'smooth' });
  else document.querySelector('.settings').scrollIntoView({ behavior: 'smooth' });
}));

if ('mediaSession' in navigator) {
  navigator.mediaSession.setActionHandler('play', () => {
    playback.userPaused = false;
    playback.intendedPlaying = true;
    els.audio.play().catch(() => scheduleReconnect('Přehrávání se nepodařilo obnovit'));
  });
  navigator.mediaSession.setActionHandler('pause', () => {
    playback.userPaused = true;
    playback.intendedPlaying = false;
    clearTimer('interruptionTimer');
    els.audio.pause();
    setMediaPlaybackState('paused');
  });
  navigator.mediaSession.setActionHandler('stop', stop);
  setMediaPlaybackState('none');
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') resumeAfterInterruption();
});
window.addEventListener('pageshow', resumeAfterInterruption);
window.addEventListener('focus', resumeAfterInterruption);

loadStations();
