/**
 * Tarango Desktop — Main Application
 * Port of the Android IPTV app to Electron
 *
 * Features ported:
 *  - M3U parser (with DRM skip)
 *  - Multi-source with cache-first loading + background refresh
 *  - Category filtering
 *  - Debounced search
 *  - Favorites (add / remove / persist)
 *  - Custom user-added sources (add / delete / persist)
 *  - Fullscreen HLS player with channel zapping, number-key jump, banner overlay
 *  - Preferences persistence (localStorage)
 */

const { ipcRenderer } = require('electron');
const Hls = require('hls.js');
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

// ============================================================
// Helpers
// ============================================================
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

function getUserDataPath() {
  try {
    return ipcRenderer.sendSync('get-user-data-path');
  } catch {
    return path.join(require('os').homedir(), '.tarango-desktop');
  }
}

const DATA_DIR = getUserDataPath();
const CACHE_DIR = path.join(DATA_DIR, 'cache');

// Ensure cache directory exists
if (!fs.existsSync(CACHE_DIR)) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
}

// ============================================================
// Window Controls
// ============================================================
$('#btn-minimize').addEventListener('click', () => ipcRenderer.send('window-minimize'));
$('#btn-maximize').addEventListener('click', () => ipcRenderer.send('window-maximize'));
$('#btn-close').addEventListener('click', () => ipcRenderer.send('window-close'));

// ============================================================
// Toast
// ============================================================
let toastTimeout = null;
function showToast(msg) {
  const toast = $('#toast');
  toast.textContent = msg;
  toast.classList.add('visible');
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => toast.classList.remove('visible'), 2500);
}

// ============================================================
// Confirm Dialog
// ============================================================
function showConfirm(title, message) {
  return new Promise((resolve) => {
    $('#confirm-title').textContent = title;
    $('#confirm-message').textContent = message;
    $('#modal-confirm').classList.add('active');
    const yes = $('#confirm-yes');
    const no = $('#confirm-no');
    function cleanup() {
      $('#modal-confirm').classList.remove('active');
      yes.removeEventListener('click', onYes);
      no.removeEventListener('click', onNo);
    }
    function onYes() { cleanup(); resolve(true); }
    function onNo() { cleanup(); resolve(false); }
    yes.addEventListener('click', onYes);
    no.addEventListener('click', onNo);
  });
}

// ============================================================
// M3U Parser
// ============================================================
function parseM3U(raw) {
  const channels = [];
  if (!raw) return channels;
  const lines = raw.split(/\r?\n/);
  let pendingName = null;
  let pendingGroup = 'Other';
  let pendingLogo = null;

  for (const lineRaw of lines) {
    const line = lineRaw.trim();
    if (!line) continue;

    if (line.startsWith('#EXTINF')) {
      pendingGroup = extractAttr(line, 'group-title') || 'Other';
      pendingLogo = extractAttr(line, 'tvg-logo');
      if (pendingLogo) {
        pendingLogo = pendingLogo.trim();
        if (!pendingLogo.startsWith('http')) pendingLogo = null;
      }
      const comma = line.lastIndexOf(',');
      pendingName = (comma >= 0 && comma < line.length - 1)
        ? line.substring(comma + 1).trim()
        : '';
      if (pendingName.startsWith('#KODIPROP') || pendingName.includes('license_key')) {
        pendingName = null;
      }
      continue;
    }

    if (line.startsWith('#')) {
      if (line.includes('license_key') || line.startsWith('#KODIPROP')) {
        pendingName = null;
      }
      continue;
    }

    // URL line
    if (pendingName && pendingName.length > 0 && isPlayableUrl(line)) {
      channels.push({ name: pendingName, url: line, group: pendingGroup, logo: pendingLogo });
    }
    pendingName = null;
    pendingGroup = 'Other';
    pendingLogo = null;
  }
  return channels;
}

function extractAttr(line, attr) {
  const key = attr + '="';
  const start = line.indexOf(key);
  if (start < 0) return null;
  const valStart = start + key.length;
  const end = line.indexOf('"', valStart);
  if (end < 0) return null;
  return line.substring(valStart, end).trim();
}

function isPlayableUrl(url) {
  return url.startsWith('http://') || url.startsWith('https://');
}

// ============================================================
// Network fetch
// ============================================================
function fetchUrl(urlStr, timeoutMs = 12000) {
  return new Promise((resolve) => {
    try {
      const mod = urlStr.startsWith('https') ? https : http;
      const req = mod.get(urlStr, {
        timeout: timeoutMs,
        headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Tarango/1.0' }
      }, (res) => {
        // Follow redirects
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          fetchUrl(res.headers.location, timeoutMs).then(resolve);
          return;
        }
        if (res.statusCode !== 200) { resolve(null); return; }
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => data += chunk);
        res.on('end', () => resolve(data));
        res.on('error', () => resolve(null));
      });
      req.on('error', () => resolve(null));
      req.on('timeout', () => { req.destroy(); resolve(null); });
    } catch {
      resolve(null);
    }
  });
}

// ============================================================
// File I/O (cache)
// ============================================================
function readCacheFile(filename) {
  try {
    const fp = path.join(CACHE_DIR, filename);
    if (fs.existsSync(fp)) return fs.readFileSync(fp, 'utf8');
  } catch {}
  return null;
}

function writeCacheFile(filename, content) {
  try {
    const fp = path.join(CACHE_DIR, filename);
    const tmp = fp + '.tmp';
    fs.writeFileSync(tmp, content, 'utf8');
    fs.renameSync(tmp, fp);
  } catch {}
}

// ============================================================
// Bundled asset reading (from the assets/ folder shipped with app)
// ============================================================
function readBundledAsset(filename) {
  if (!filename) return null;
  try {
    const fp = path.join(__dirname, 'assets', filename);
    if (fs.existsSync(fp)) return fs.readFileSync(fp, 'utf8');
  } catch {}
  return null;
}

// ============================================================
// Preferences (localStorage wrapper)
// ============================================================
const Prefs = {
  get(key, def = null) {
    try { const v = localStorage.getItem('tarango_' + key); return v !== null ? v : def; } catch { return def; }
  },
  set(key, val) {
    try { localStorage.setItem('tarango_' + key, val); } catch {}
  },
  getInt(key, def = 0) {
    const v = this.get(key);
    const n = parseInt(v, 10);
    return isNaN(n) ? def : n;
  },
  lastSourceIndex() { return this.getInt('source_index', 0); },
  saveSourceIndex(i) { this.set('source_index', i); },
  lastCategory() { return this.get('category', 'All'); },
  saveCategory(c) { this.set('category', c); },
};

// ============================================================
// Favorites
// ============================================================
const SEP = '\u0001';
const Favorites = {
  _lines: [],
  _urls: new Set(),

  init() {
    const stored = Prefs.get('favorites_ordered', '');
    if (stored) {
      for (const line of stored.split('\n')) {
        if (line.trim()) this._addLine(line);
      }
    }
  },

  _addLine(line) {
    this._lines.push(line);
    const url = this._urlOf(line);
    if (url) this._urls.add(url);
  },

  _urlOf(line) {
    const i = line.indexOf(SEP);
    return i >= 0 ? line.substring(0, i) : line;
  },

  isFavorite(url) {
    return url && this._urls.has(url);
  },

  add(channel) {
    if (!channel || !channel.url || this.isFavorite(channel.url)) return;
    const line = channel.url + SEP + (channel.name || '') + SEP + (channel.group || '') + SEP + (channel.logo || '');
    this._lines.push(line);
    this._urls.add(channel.url);
    this._persist();
  },

  remove(url) {
    if (!url) return;
    const idx = this._lines.findIndex(l => this._urlOf(l) === url);
    if (idx >= 0) {
      this._lines.splice(idx, 1);
      this._urls.delete(url);
      this._persist();
    }
  },

  list() {
    return this._lines.map(line => {
      const parts = line.split(SEP);
      return {
        url: parts[0] || '',
        name: parts[1] || parts[0] || 'Unknown',
        group: parts[2] || 'Favorites',
        logo: (parts[3] && parts[3].length > 0) ? parts[3] : null
      };
    }).filter(c => c.url);
  },

  _persist() {
    Prefs.set('favorites_ordered', this._lines.join('\n'));
  }
};

// ============================================================
// Custom Sources
// ============================================================
const CustomSources = {
  _entries: [], // [{name, url}]

  init() {
    const stored = Prefs.get('custom_sources', '');
    if (stored) {
      for (const line of stored.split('\n')) {
        if (!line.trim()) continue;
        const parts = line.split(SEP, 2);
        if (parts.length === 2 && parts[1].trim()) {
          this._entries.push({ name: parts[0], url: parts[1] });
        }
      }
    }
  },

  entries() { return [...this._entries]; },

  exists(url) {
    return this._entries.some(e => e.url === url);
  },

  add(name, url) {
    name = (name || '').trim().replace(SEP, ' ').replace('\n', ' ') || 'My Source';
    url = (url || '').trim();
    if (!url || this.exists(url)) return false;
    this._entries.push({ name, url });
    this._persist();
    return true;
  },

  remove(url) {
    const idx = this._entries.findIndex(e => e.url === url);
    if (idx >= 0) {
      this._entries.splice(idx, 1);
      this._persist();
    }
  },

  asSources() {
    return this._entries.map(e => ({
      name: e.name,
      url: e.url,
      cacheFile: 'cache_custom_' + hashCode(e.url).toString(16) + '.m3u8',
      assetFile: null
    }));
  },

  _persist() {
    Prefs.set('custom_sources', this._entries.map(e => e.name + SEP + e.url).join('\n'));
  }
};

function hashCode(s) {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    hash = ((hash << 5) - hash) + s.charCodeAt(i);
    hash |= 0; // Convert to 32bit integer
  }
  return Math.abs(hash);
}

// ============================================================
// Playlist Sources
// ============================================================
function builtInSources() {
  return [
    {
      name: 'Source 1',
      url: 'https://raw.githubusercontent.com/imShakil/tvlink/main/iptv.m3u8',
      cacheFile: 'cache_bangla.m3u8',
      assetFile: 'iptv.m3u8'
    },
    {
      name: 'Source 2',
      url: 'https://raw.githubusercontent.com/ashik4u/mrgify-clean/main/playlist.m3u',
      cacheFile: 'cache_sports.m3u8',
      assetFile: 'sports.m3u8'
    },
    {
      name: 'My List',
      url: 'https://raw.githubusercontent.com/shahriar-ahmed-seam/IPTV-lite/main/playlist.m3u',
      cacheFile: 'cache_mylist.m3u8',
      assetFile: 'mylist.m3u8'
    }
  ];
}

function allSources() {
  return [...builtInSources(), ...CustomSources.asSources()];
}

// ============================================================
// Channel Repository
// ============================================================
const ALL = 'All';
const SEARCH_KEY = '__search__';
const FAVORITES_KEY = '__favorites__';

const ChannelRepo = {
  all: [],
  byCategory: {},
  currentSource: null,
  loadedRaw: null,

  isLoaded() { return this.all.length > 0; },

  getCategories() {
    return Object.keys(this.byCategory).filter(k => k !== SEARCH_KEY && k !== FAVORITES_KEY);
  },

  getChannels(category) {
    return this.byCategory[category] || [];
  },

  setBucket(key, channels) {
    this.byCategory[key] = [...channels];
  },

  search(query) {
    const q = (query || '').trim().toLowerCase();
    const results = [];
    if (!q) { this.byCategory[SEARCH_KEY] = results; return results; }
    for (const c of this.all) {
      if (c.name && c.name.toLowerCase().includes(q)) results.push(c);
    }
    this.byCategory[SEARCH_KEY] = results;
    return results;
  },

  _build(parsed) {
    this.all = [...parsed];
    this.byCategory = {};
    this.byCategory[ALL] = [...parsed];
    for (const c of parsed) {
      if (!this.byCategory[c.group]) this.byCategory[c.group] = [];
      this.byCategory[c.group].push(c);
    }
  },

  async load(source, listener) {
    this.currentSource = source;

    // Step 1: instant local load
    let local = readCacheFile(source.cacheFile);
    let fromCache = local && local.includes('#EXTINF');
    if (!fromCache) {
      local = readBundledAsset(source.assetFile);
    }

    if (local && local.includes('#EXTINF')) {
      const parsed = parseM3U(local);
      if (parsed.length > 0) {
        this._build(parsed);
        this.loadedRaw = local;
        listener.onReady(parsed.length, fromCache);
      } else {
        this.loadedRaw = null;
      }
    } else {
      this.loadedRaw = null;
      this.all = [];
      this.byCategory = {};
    }

    // Step 2: background network refresh
    const shownRaw = this.loadedRaw;
    const raw = await fetchUrl(source.url);

    if (!raw || !raw.includes('#EXTINF')) {
      if (!shownRaw) listener.onEmpty();
      return;
    }

    if (raw === shownRaw) {
      writeCacheFile(source.cacheFile, raw);
      return;
    }

    const parsed = parseM3U(raw);
    if (parsed.length === 0) {
      if (!shownRaw) listener.onEmpty();
      return;
    }

    writeCacheFile(source.cacheFile, raw);

    // Only apply if still on the same source
    if (this.currentSource === source) {
      this._build(parsed);
      this.loadedRaw = raw;
      listener.onUpdated(parsed.length);
    }
  }
};

// ============================================================
// Logo Cache (in-memory for the session)
// ============================================================
const logoCache = {};
const logoFailed = new Set();

function getLogoUrl(logoUrl) {
  if (!logoUrl || logoFailed.has(logoUrl)) return null;
  return logoUrl;
}

// ============================================================
// UI State
// ============================================================
let sources = [];
let currentSourceIndex = 0;
let currentCategory = ALL;
let uiBuilt = false;
let favoritesMode = false;
let searchMode = false;
let searchTimeout = null;
const SEARCH_DEBOUNCE_MS = 220;

// Player state
let playerChannels = [];
let playerCategory = '';
let playerIndex = 0;
let hls = null;
let bannerTimeout = null;
let numberBuffer = '';
let numberTimeout = null;

// ============================================================
// Initialize
// ============================================================
document.addEventListener('DOMContentLoaded', () => {
  Favorites.init();
  CustomSources.init();

  sources = allSources();
  currentSourceIndex = Math.min(Math.max(0, Prefs.lastSourceIndex()), sources.length - 1);

  setupSourceSpinner();
  setupSearch();
  setupFavorites();
  setupAddSource();
  setupPlayer();

  // Load default source with splash
  loadSource(sources[currentSourceIndex], false);

  // Warm all sources in background
  warmAllSources();
});

// ============================================================
// Source Spinner
// ============================================================
function setupSourceSpinner() {
  rebuildSourceOptions();
  const select = $('#source-select');
  select.addEventListener('change', () => {
    const newIdx = parseInt(select.value, 10);
    if (newIdx === currentSourceIndex) return;
    currentSourceIndex = newIdx;
    Prefs.saveSourceIndex(newIdx);
    currentCategory = ALL;
    switchToSource(sources[newIdx]);
  });
}

function rebuildSourceOptions() {
  sources = allSources();
  if (currentSourceIndex >= sources.length) currentSourceIndex = 0;
  const select = $('#source-select');
  select.innerHTML = sources.map((s, i) =>
    `<option value="${i}" ${i === currentSourceIndex ? 'selected' : ''}>${s.name}</option>`
  ).join('');
}

// ============================================================
// Search
// ============================================================
function setupSearch() {
  const searchBox = $('#search-box');
  searchBox.addEventListener('input', () => {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => applySearch(searchBox.value), SEARCH_DEBOUNCE_MS);
  });
}

function applySearch(query) {
  if (!uiBuilt) return;
  const q = (query || '').trim();

  if (!q) {
    if (searchMode) {
      searchMode = false;
      showCategory(currentCategory);
    }
    return;
  }

  searchMode = true;
  favoritesMode = false;
  $('#btn-favorites').classList.remove('active');
  const results = ChannelRepo.search(q);
  renderGrid(results);

  if (results.length === 0) {
    showStatus(`No channels match "${q}"`);
  } else {
    hideStatus();
  }
}

// ============================================================
// Favorites
// ============================================================
function setupFavorites() {
  $('#btn-favorites').addEventListener('click', toggleFavoritesView);
}

function toggleFavoritesView() {
  if (favoritesMode) {
    favoritesMode = false;
    $('#btn-favorites').classList.remove('active');
    showCategory(currentCategory);
  } else {
    showFavoritesView();
  }
}

function showFavoritesView() {
  favoritesMode = true;
  searchMode = false;
  $('#btn-favorites').classList.add('active');
  const searchBox = $('#search-box');
  if (searchBox.value) searchBox.value = '';

  const favs = Favorites.list();
  ChannelRepo.setBucket(FAVORITES_KEY, favs);
  renderGrid(favs);

  if (favs.length === 0) {
    showStatus('No favourites yet.\nClick the star ★ on any channel to add it,\nthen pick a category above to go back.');
  } else {
    hideStatus();
  }
}

async function onStarClicked(channel) {
  if (!channel) return;
  const isFav = Favorites.isFavorite(channel.url);

  if (isFav && favoritesMode) {
    const ok = await showConfirm('Remove favourite', `Remove "${channel.name}" from favourites?`);
    if (ok) {
      Favorites.remove(channel.url);
      showFavoritesView();
      showToast('Removed from favourites');
    }
  } else if (isFav) {
    const ok = await showConfirm('Remove favourite', `"${channel.name}" is already in favourites. Remove it?`);
    if (ok) {
      Favorites.remove(channel.url);
      refreshStars();
      showToast('Removed from favourites');
    }
  } else {
    const ok = await showConfirm('Add favourite', `Add "${channel.name}" to favourites?`);
    if (ok) {
      Favorites.add(channel);
      refreshStars();
      showToast('Added to favourites');
    }
  }
}

function refreshStars() {
  document.querySelectorAll('.channel-tile').forEach(tile => {
    const url = tile.dataset.url;
    const star = tile.querySelector('.tile-star');
    if (star && url) {
      if (Favorites.isFavorite(url)) {
        star.classList.add('favorited');
      } else {
        star.classList.remove('favorited');
      }
    }
  });
}

// ============================================================
// Custom Sources Modal
// ============================================================
function setupAddSource() {
  $('#btn-add-source').addEventListener('click', openSourcesModal);
  $('#btn-sources-close').addEventListener('click', closeSourcesModal);
  $('#btn-add-source-confirm').addEventListener('click', addCustomSource);

  // Close on backdrop click
  $('#modal-sources').addEventListener('click', (e) => {
    if (e.target === $('#modal-sources')) closeSourcesModal();
  });
}

function openSourcesModal() {
  renderCustomSourcesList();
  $('#modal-sources').classList.add('active');
}

function closeSourcesModal() {
  $('#modal-sources').classList.remove('active');
}

function addCustomSource() {
  const nameInput = $('#input-source-name');
  const urlInput = $('#input-source-url');
  const name = nameInput.value.trim();
  const url = urlInput.value.trim();

  if (!url || !(url.startsWith('http://') || url.startsWith('https://'))) {
    showToast('Enter a valid http(s) playlist URL');
    return;
  }

  if (CustomSources.exists(url)) {
    showToast('That source is already added');
    return;
  }

  CustomSources.add(name, url);
  rebuildSourceOptions();
  renderCustomSourcesList();
  nameInput.value = '';
  urlInput.value = '';

  // Warm the new source
  const newSources = allSources();
  for (const s of newSources) {
    if (s.url === url) {
      warmSource(s);
      break;
    }
  }
  showToast('Source added');
}

function renderCustomSourcesList() {
  const container = $('#custom-sources-list');
  const entries = CustomSources.entries();

  if (entries.length === 0) {
    container.innerHTML = '<div class="custom-list-empty">No custom sources added yet.</div>';
    return;
  }

  container.innerHTML = entries.map(e => `
    <div class="custom-source-item" data-url="${escapeHtml(e.url)}">
      <span class="custom-source-name">${escapeHtml(e.name)}</span>
      <button class="custom-source-delete" title="Remove">✕</button>
    </div>
  `).join('');

  container.querySelectorAll('.custom-source-delete').forEach(btn => {
    btn.addEventListener('click', () => {
      const url = btn.closest('.custom-source-item').dataset.url;
      const entry = entries.find(e => e.url === url);
      CustomSources.remove(url);
      rebuildSourceOptions();
      renderCustomSourcesList();
      showToast('Removed ' + (entry ? entry.name : 'source'));
    });
  });
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ============================================================
// Source Loading
// ============================================================
function loadSource(source, isSwitch) {
  if (!ChannelRepo.isLoaded() || isSwitch) {
    showStatus('Loading ' + source.name + '...');
  }

  ChannelRepo.load(source, {
    onReady(count, fromCache) {
      buildUi();
      hideSplash();
    },
    onUpdated(count) {
      refreshAfterUpdate();
    },
    onEmpty() {
      showStatus('Could not load ' + source.name + '.\nCheck the internet connection.');
      hideSplash();
    }
  });
}

function switchToSource(source) {
  uiBuilt = false;
  searchMode = false;
  favoritesMode = false;
  $('#btn-favorites').classList.remove('active');
  const searchBox = $('#search-box');
  if (searchBox.value) searchBox.value = '';
  loadSource(source, true);
}

function hideSplash() {
  setTimeout(() => {
    $('#splash-screen').classList.add('hidden');
    $('#app').classList.add('visible');
  }, 600);
}

// ============================================================
// UI Building
// ============================================================
function buildUi() {
  hideStatus();
  const cats = ChannelRepo.getCategories();
  renderCategories(cats);

  if (!cats.includes(currentCategory)) currentCategory = ALL;
  showCategory(currentCategory);

  uiBuilt = true;
}

function refreshAfterUpdate() {
  const cats = ChannelRepo.getCategories();
  renderCategories(cats);
  if (!cats.includes(currentCategory)) currentCategory = ALL;

  if (favoritesMode) return;
  if (searchMode) {
    applySearch($('#search-box').value);
    return;
  }

  renderGrid(ChannelRepo.getChannels(currentCategory));
}

function renderCategories(cats) {
  const bar = $('#category-bar');
  bar.innerHTML = cats.map(cat => `
    <div class="category-chip ${cat === currentCategory ? 'selected' : ''}" data-cat="${escapeHtml(cat)}">
      <span>${escapeHtml(cat)}</span>
    </div>
  `).join('');

  bar.querySelectorAll('.category-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const cat = chip.dataset.cat;
      showCategory(cat);
    });
  });
}

function showCategory(category) {
  currentCategory = category;
  Prefs.saveCategory(category);
  favoritesMode = false;
  searchMode = false;
  $('#btn-favorites').classList.remove('active');
  const searchBox = $('#search-box');
  if (searchBox.value) searchBox.value = '';

  // Update chip selection
  document.querySelectorAll('.category-chip').forEach(chip => {
    chip.classList.toggle('selected', chip.dataset.cat === category);
  });

  hideStatus();
  const channels = ChannelRepo.getChannels(category);
  renderGrid(channels);
}

function renderGrid(channels) {
  const grid = $('#channel-grid');
  const container = $('#grid-container');

  if (channels.length === 0 && !favoritesMode && !searchMode) {
    grid.innerHTML = '';
    return;
  }

  grid.innerHTML = channels.map((ch, i) => {
    const isFav = Favorites.isFavorite(ch.url);
    const initial = ch.name ? ch.name.charAt(0).toUpperCase() : '?';
    const logoHtml = ch.logo
      ? `<img class="tile-logo" src="${escapeHtml(ch.logo)}" alt="" loading="lazy" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'"><div class="tile-logo-placeholder" style="display:none">${escapeHtml(initial)}</div>`
      : `<div class="tile-logo-placeholder">${escapeHtml(initial)}</div>`;

    return `
      <div class="channel-tile" data-index="${i}" data-url="${escapeHtml(ch.url)}">
        ${logoHtml}
        <div class="tile-name">${escapeHtml(ch.name)}</div>
        <div class="tile-number">#${i + 1}</div>
        <button class="tile-star ${isFav ? 'favorited' : ''}" title="Favourite">★</button>
      </div>
    `;
  }).join('');

  container.style.display = '';
  container.scrollTop = 0;

  // Attach click handlers
  grid.querySelectorAll('.channel-tile').forEach(tile => {
    const idx = parseInt(tile.dataset.index, 10);
    tile.addEventListener('click', (e) => {
      // Don't open player when clicking star
      if (e.target.closest('.tile-star')) return;
      openPlayer(idx);
    });
    tile.addEventListener('dblclick', (e) => {
      if (e.target.closest('.tile-star')) return;
      openPlayer(idx);
    });
  });

  grid.querySelectorAll('.tile-star').forEach(star => {
    star.addEventListener('click', (e) => {
      e.stopPropagation();
      const tile = star.closest('.channel-tile');
      const idx = parseInt(tile.dataset.index, 10);
      if (idx >= 0 && idx < channels.length) {
        onStarClicked(channels[idx]);
      }
    });
  });
}

function showStatus(msg) {
  const status = $('#status-text');
  status.textContent = msg;
  status.classList.add('visible');
  $('#grid-container').style.display = 'none';
}

function hideStatus() {
  $('#status-text').classList.remove('visible');
  $('#grid-container').style.display = '';
}

// ============================================================
// Player
// ============================================================
function setupPlayer() {
  $('#player-back').addEventListener('click', closePlayer);

  document.addEventListener('keydown', (e) => {
    const overlay = $('#player-overlay');
    if (!overlay.classList.contains('active')) {
      // Escape in main view exits favorites/search
      if (e.key === 'Escape') {
        if (favoritesMode) { toggleFavoritesView(); e.preventDefault(); return; }
        if (searchMode) { $('#search-box').value = ''; applySearch(''); e.preventDefault(); return; }
      }
      return;
    }

    switch (e.key) {
      case 'ArrowUp':
        prevChannel();
        e.preventDefault();
        break;
      case 'ArrowDown':
        nextChannel();
        e.preventDefault();
        break;
      case 'Escape':
      case 'Backspace':
        closePlayer();
        e.preventDefault();
        break;
      case 'Enter':
        showBanner((playerIndex + 1) + '.  ' + playerChannels[playerIndex].name);
        e.preventDefault();
        break;
      default:
        if (e.key >= '0' && e.key <= '9') {
          onDigit(parseInt(e.key, 10));
          e.preventDefault();
        }
        break;
    }
  });
}

function openPlayer(positionInCategory) {
  let cat;
  if (favoritesMode) {
    cat = FAVORITES_KEY;
  } else if (searchMode) {
    cat = SEARCH_KEY;
  } else {
    cat = currentCategory;
  }

  playerChannels = ChannelRepo.getChannels(cat);
  playerCategory = cat;
  playerIndex = positionInCategory;

  if (playerChannels.length === 0) return;
  if (playerIndex < 0 || playerIndex >= playerChannels.length) playerIndex = 0;

  $('#player-overlay').classList.add('active');
  playCurrent();
}

function closePlayer() {
  $('#player-overlay').classList.remove('active');
  destroyHls();
  const video = $('#player-video');
  video.pause();
  video.removeAttribute('src');
  video.load();
}

function playCurrent() {
  const channel = playerChannels[playerIndex];
  if (!channel) return;

  showPlayerStatus('Opening ' + channel.name + '...');
  showBanner((playerIndex + 1) + '.  ' + channel.name);
  updatePlayerChannelBar(channel);
  playStream(channel.url);
}

function playStream(url) {
  const video = $('#player-video');
  destroyHls();

  // Reset video
  video.pause();
  video.removeAttribute('src');

  if (Hls.isSupported() && (url.includes('.m3u8') || url.includes('.m3u'))) {
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      maxBufferLength: 30,
      maxMaxBufferLength: 60,
      startLevel: -1
    });

    hls.loadSource(url);
    hls.attachMedia(video);

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      video.play().catch(() => {});
    });

    hls.on(Hls.Events.ERROR, (event, data) => {
      if (data.fatal) {
        showPlayerStatus('Channel unavailable. Press ↑/↓ for another.');
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          hls.startLoad();
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          hls.recoverMediaError();
        } else {
          destroyHls();
        }
      }
    });
  } else {
    // Direct stream (MP4/TS/etc)
    video.src = url;
    video.play().catch(() => {
      showPlayerStatus('Channel unavailable. Press ↑/↓ for another.');
    });
  }

  // Listen for video events
  video.onplaying = () => hidePlayerStatus();
  video.onwaiting = () => showPlayerStatus('Buffering...');
  video.onerror = () => showPlayerStatus('Channel unavailable. Press ↑/↓ for another.');
}

function destroyHls() {
  if (hls) {
    hls.destroy();
    hls = null;
  }
}

function nextChannel() {
  playerIndex = (playerIndex + 1) % playerChannels.length;
  playCurrent();
}

function prevChannel() {
  playerIndex = (playerIndex - 1 + playerChannels.length) % playerChannels.length;
  playCurrent();
}

function showBanner(text) {
  const banner = $('#player-banner');
  banner.textContent = text;
  banner.classList.add('visible');
  clearTimeout(bannerTimeout);
  bannerTimeout = setTimeout(() => banner.classList.remove('visible'), 3000);
}

function showPlayerStatus(text) {
  const status = $('#player-status');
  status.textContent = text;
  status.classList.add('visible');
}

function hidePlayerStatus() {
  $('#player-status').classList.remove('visible');
}

function updatePlayerChannelBar(channel) {
  const logo = $('#player-ch-logo');
  const name = $('#player-ch-name');
  const group = $('#player-ch-group');

  name.textContent = channel.name;
  group.textContent = channel.group;

  if (channel.logo) {
    logo.src = channel.logo;
    logo.style.display = '';
  } else {
    logo.style.display = 'none';
  }
}

// Number key jump
function onDigit(digit) {
  numberBuffer += digit;
  const numEl = $('#player-number');
  numEl.textContent = numberBuffer;
  numEl.classList.add('visible');
  clearTimeout(numberTimeout);
  numberTimeout = setTimeout(commitNumberJump, 1500);
}

function commitNumberJump() {
  const numEl = $('#player-number');
  numEl.classList.remove('visible');
  if (!numberBuffer) return;
  const num = parseInt(numberBuffer, 10);
  numberBuffer = '';
  if (num >= 1 && num <= playerChannels.length) {
    playerIndex = num - 1;
    playCurrent();
  } else {
    showBanner('No channel ' + num);
  }
}

// ============================================================
// Background warming
// ============================================================
async function warmAllSources() {
  for (const s of sources) {
    try {
      const raw = await fetchUrl(s.url);
      if (raw && raw.includes('#EXTINF')) {
        writeCacheFile(s.cacheFile, raw);
      }
    } catch {}
    await new Promise(r => setTimeout(r, 50));
  }
}

async function warmSource(s) {
  try {
    const raw = await fetchUrl(s.url);
    if (raw && raw.includes('#EXTINF')) {
      writeCacheFile(s.cacheFile, raw);
    }
  } catch {}
}
