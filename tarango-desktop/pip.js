const { ipcRenderer } = require('electron');
const Hls = require('hls.js');

const $ = (sel) => document.querySelector(sel);

let hls = null;
let currentChannel = null;
let channelList = [];
let currentIndex = 0;
let bannerTimeout = null;
let idleTimeout = null;
let lastVolume = 1;

const video = $('#pip-video');
const statusEl = $('#pip-status');
const bannerEl = $('#pip-banner');
const nameEl = $('#pip-channel-name');
const countEl = $('#pip-channel-count');
const containerEl = $('#pip-container');
const sliderEl = $('#pip-volume-slider');

// ============================================================
// Channel Loading and Playback
// ============================================================
ipcRenderer.on('load-channel', (event, data) => {
  if (!data) return;
  channelList = data.channels || [];
  currentIndex = typeof data.index === 'number' ? data.index : 0;
  currentChannel = data.channel || channelList[currentIndex];

  updateChannelDisplay();
  if (currentChannel && currentChannel.url) {
    playStream(currentChannel.url);
  }
});

function updateChannelDisplay() {
  if (!currentChannel) return;
  nameEl.textContent = currentChannel.name || 'Live Stream';
  nameEl.title = currentChannel.name || '';
  if (channelList.length > 0) {
    countEl.textContent = `${currentIndex + 1}/${channelList.length}`;
  } else {
    countEl.textContent = '';
  }
  showBanner(`${currentIndex + 1}. ${currentChannel.name}`);
}

function playStream(url) {
  destroyHls();
  showStatus('Connecting...');

  video.pause();
  video.removeAttribute('src');

  if (Hls.isSupported() && (url.includes('.m3u8') || url.includes('.m3u'))) {
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      maxBufferLength: 20,
      maxMaxBufferLength: 40,
      startLevel: -1
    });

    hls.loadSource(url);
    hls.attachMedia(video);

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      video.play().catch(() => {});
      updatePlayIcons(true);
    });

    hls.on(Hls.Events.ERROR, (event, data) => {
      if (data.fatal) {
        showStatus('Stream error. Switching...');
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
    video.src = url;
    video.play().catch(() => {
      showStatus('Channel unavailable');
    });
    updatePlayIcons(true);
  }

  video.onplaying = () => {
    hideStatus();
    updatePlayIcons(true);
  };
  video.onwaiting = () => showStatus('Buffering...');
  video.onerror = () => showStatus('Playback error');
}

function destroyHls() {
  if (hls) {
    hls.destroy();
    hls = null;
  }
}

function showStatus(text) {
  statusEl.textContent = text;
  statusEl.classList.add('visible');
}

function hideStatus() {
  statusEl.classList.remove('visible');
}

function showBanner(text) {
  bannerEl.textContent = text;
  bannerEl.classList.add('visible');
  clearTimeout(bannerTimeout);
  bannerTimeout = setTimeout(() => bannerEl.classList.remove('visible'), 2400);
}

// ============================================================
// Playback Controls
// ============================================================
function togglePlay() {
  if (video.paused) {
    video.play().catch(() => {});
    updatePlayIcons(true);
  } else {
    video.pause();
    updatePlayIcons(false);
  }
}

function updatePlayIcons(isPlaying) {
  $('#icon-pip-play').style.display = isPlaying ? 'none' : 'block';
  $('#icon-pip-pause').style.display = isPlaying ? 'block' : 'none';
  $('#icon-pip-center-play').style.display = isPlaying ? 'none' : 'block';
  $('#icon-pip-center-pause').style.display = isPlaying ? 'block' : 'none';
}

function nextChannel() {
  if (channelList.length === 0) return;
  currentIndex = (currentIndex + 1) % channelList.length;
  currentChannel = channelList[currentIndex];
  updateChannelDisplay();
  playStream(currentChannel.url);
}

function prevChannel() {
  if (channelList.length === 0) return;
  currentIndex = (currentIndex - 1 + channelList.length) % channelList.length;
  currentChannel = channelList[currentIndex];
  updateChannelDisplay();
  playStream(currentChannel.url);
}

function toggleMute() {
  if (video.muted || video.volume === 0) {
    video.muted = false;
    video.volume = lastVolume || 1;
    sliderEl.value = video.volume;
    updateVolumeIcon(video.volume);
  } else {
    lastVolume = video.volume;
    video.muted = true;
    sliderEl.value = 0;
    updateVolumeIcon(0);
  }
}

function setVolume(val) {
  const v = Math.max(0, Math.min(1, parseFloat(val)));
  video.muted = (v === 0);
  video.volume = v;
  sliderEl.value = v;
  if (v > 0) lastVolume = v;
  updateVolumeIcon(v);
}

function updateVolumeIcon(vol) {
  const isMuted = video.muted || vol === 0;
  $('#icon-pip-vol-mute').style.display = isMuted ? 'block' : 'none';
  $('#icon-pip-vol-high').style.display = isMuted ? 'none' : 'block';
}

function toggleFit() {
  video.classList.toggle('cover');
  const isCover = video.classList.contains('cover');
  showBanner(isCover ? 'Aspect: Cover (Fill)' : 'Aspect: Contain (Fit)');
}

// ============================================================
// Window & Communication Handlers
// ============================================================
function restoreToMain() {
  destroyHls();
  video.pause();
  ipcRenderer.send('pip-restore-main', {
    channel: currentChannel,
    index: currentIndex,
    channels: channelList
  });
}

function closePip() {
  destroyHls();
  video.pause();
  ipcRenderer.send('pip-close');
}

function togglePin() {
  const isPinned = ipcRenderer.sendSync('pip-toggle-pin');
  const pinBtn = $('#btn-pip-pin');
  if (isPinned) {
    pinBtn.classList.add('active');
    pinBtn.title = 'Always on Top (Enabled)';
  } else {
    pinBtn.classList.remove('active');
    pinBtn.title = 'Always on Top (Disabled)';
  }
  showBanner(isPinned ? 'Window Pinned on Top' : 'Window Unpinned');
}

function minimizeMain() {
  ipcRenderer.send('pip-minimize-main');
  showBanner('Main App Minimized');
}

// Activity & Idle control bar fading
function resetIdleTimer() {
  containerEl.classList.add('controls-active');
  clearTimeout(idleTimeout);
  idleTimeout = setTimeout(() => {
    containerEl.classList.remove('controls-active');
  }, 2600);
}

// ============================================================
// Event Listeners
// ============================================================
$('#btn-pip-play').addEventListener('click', togglePlay);
$('#btn-pip-center-play').addEventListener('click', togglePlay);
$('#btn-pip-prev').addEventListener('click', prevChannel);
$('#btn-pip-next').addEventListener('click', nextChannel);
$('#btn-pip-mute').addEventListener('click', toggleMute);
$('#btn-pip-fit').addEventListener('click', toggleFit);
$('#btn-pip-pin').addEventListener('click', togglePin);
$('#btn-pip-restore').addEventListener('click', restoreToMain);
$('#btn-pip-minimize-main').addEventListener('click', minimizeMain);
$('#btn-pip-close').addEventListener('click', closePip);

sliderEl.addEventListener('input', (e) => setVolume(e.target.value));

window.addEventListener('mousemove', resetIdleTimer);
window.addEventListener('mousedown', resetIdleTimer);

// Keyboard Shortcuts
document.addEventListener('keydown', (e) => {
  resetIdleTimer();
  switch (e.key) {
    case ' ':
      togglePlay();
      e.preventDefault();
      break;
    case 'ArrowUp':
    case '[':
      prevChannel();
      e.preventDefault();
      break;
    case 'ArrowDown':
    case ']':
      nextChannel();
      e.preventDefault();
      break;
    case 'ArrowLeft':
      setVolume(video.volume - 0.1);
      e.preventDefault();
      break;
    case 'ArrowRight':
      setVolume(video.volume + 0.1);
      e.preventDefault();
      break;
    case 'm':
    case 'M':
      toggleMute();
      e.preventDefault();
      break;
    case 'r':
    case 'R':
      restoreToMain();
      e.preventDefault();
      break;
    case 'Escape':
      closePip();
      e.preventDefault();
      break;
  }
});

// Video double click for fit toggle
video.addEventListener('dblclick', toggleFit);
