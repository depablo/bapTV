/*
 * HbbTV/OIPF compatibility layer for WebView: <object type="application/oipf*"> objects,
 * "video/broadcast" and A/V objects, VK_* key constants and the key set (keyset).
 * The TV picture is a TvView under the transparent WebView; control goes through the HbbNative bridge.
 */
(function () {
  'use strict';
  if (window.__dvbtHbb) return;
  // Bridge to the app via a key known only to this layer (HbbTvBrowser substitutes it for __DVBT_NONCE__):
  // ad frames from other domains also see window.HbbNative, but without the key their calls are rejected.
  var RAW = window.HbbNative, NONCE = '__DVBT_NONCE__', N = {};
  ['log', 'version', 'setKeyset', 'show', 'hide', 'destroyApplication', 'createApplication', 'channel', 'channels',
   'setChannel', 'channelStep', 'stopBroadcast', 'releaseBroadcast', 'bindBroadcast', 'setFullScreen', 'broadcastRect']
    .forEach(function (m) {
      N[m] = function () { return RAW[m].apply(RAW, [NONCE].concat(Array.prototype.slice.call(arguments))); };
    });
  function log(m) { try { N.log(String(m)); } catch (e) {} }
  function parse(s, d) { try { return JSON.parse(s); } catch (e) { return d; } }

  // ---- Keys ----
  var VK = {
    VK_RED: 403, VK_GREEN: 404, VK_YELLOW: 405, VK_BLUE: 406,
    VK_LEFT: 37, VK_UP: 38, VK_RIGHT: 39, VK_DOWN: 40, VK_ENTER: 13, VK_BACK: 461,
    VK_PLAY: 415, VK_PAUSE: 19, VK_PLAY_PAUSE: 402, VK_STOP: 413, VK_FAST_FWD: 417, VK_REWIND: 412,
    VK_NEXT: 425, VK_PREV: 424, VK_PAGE_UP: 33, VK_PAGE_DOWN: 34, VK_INFO: 457, VK_TELETEXT: 459,
    VK_SUBTITLE: 460, VK_EXIT: 462, VK_MENU: 462, VK_CHANNEL_UP: 427, VK_CHANNEL_DOWN: 428,
    VK_UP_CHANNEL: 427, VK_DOWN_CHANNEL: 428, VK_GUIDE: 458, VK_RECORD: 416, VK_MUTE: 449,
    VK_AUDIO_TRACK: 461, VK_AUDIO_DESC: 0, VK_CHANNELS: 0
  };
  for (var i = 0; i <= 9; i++) VK['VK_' + i] = 48 + i;
  window.KeyEvent = window.KeyEvent || {};
  Object.keys(VK).forEach(function (k) {
    try { if (window.KeyEvent[k] === undefined) Object.defineProperty(window.KeyEvent, k, { value: VK[k] }); } catch (e) {}
    if (window[k] === undefined) window[k] = VK[k];
  });

  function sendKey(code) {
    var target = document.activeElement || document.body || document.documentElement;
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      var e = new KeyboardEvent(type, { bubbles: true, cancelable: true });
      Object.defineProperty(e, 'keyCode', { get: function () { return code; } });
      Object.defineProperty(e, 'which', { get: function () { return code; } });
      Object.defineProperty(e, 'charCode', { get: function () { return type === 'keypress' ? code : 0; } });
      target.dispatchEvent(e);
    });
  }

  // ---- Key set and application ----
  var keyset = {
    RED: 0x1, GREEN: 0x2, YELLOW: 0x4, BLUE: 0x8, NAVIGATION: 0x10, VCR: 0x20, SCROLL: 0x40,
    INFO: 0x80, NUMERIC: 0x100, ALPHA: 0x200, OTHER: 0x400,
    value: 0, maximumValue: 0x7FF, otherKeys: [], maximumOtherKeys: [], supportsPointer: false,
    setValue: function (v, other) { this.value = v | 0; this.otherKeys = other || []; N.setKeyset(this.value); return this.value; },
    setPointer: function () {}
  };

  function channelObject(c) {
    if (!c) return null;
    return {
      channelType: c.radio ? 1 : 0, idType: 12, ccid: c.ccid, name: c.name,
      onid: c.onid, tsid: c.tsid, sid: c.sid, nid: c.onid, majorChannel: c.number, minorChannel: 0,
      dsd: '', locked: false, manualBlock: false, hidden: false, ipBroadcastID: '', longName: c.name,
      description: '', authorised: true, genre: [], isHD: !c.radio, logoURL: '', favourite: false, favIDs: []
    };
  }
  function currentChannel() { return channelObject(parse(N.channel(), null)); }
  function channelList() {
    var list = parse(N.channels(), []).map(channelObject);
    list.item = function (i) { return list[i]; };
    list.getChannel = function (ccid) { return list.filter(function (c) { return c.ccid === ccid; })[0] || null; };
    list.getChannelByTriplet = function (onid, tsid, sid) {
      return list.filter(function (c) { return c.onid === onid && (tsid === undefined || tsid === null || c.tsid === tsid) && c.sid === sid; })[0] || null;
    };
    return list;
  }

  var app = {
    visible: true,
    privateData: { keyset: keyset, get currentChannel() { return currentChannel(); }, getFreeMem: function () { return 128 * 1024 * 1024; } },
    show: function () { this.visible = true; N.show(); return true; },
    hide: function () { this.visible = false; N.hide(); return true; },
    createApplication: function (uri, child) { log('createApplication ' + uri); N.createApplication(String(uri)); return app; },
    destroyApplication: function () { log('destroyApplication'); N.destroyApplication(); },
    activateInput: function () {}, deactivateInput: function () {},
    addEventListener: function () {}, removeEventListener: function () {}
  };

  var capabilitiesXml = '<profilelist><ui_profile name="OITF_HD_UIPROF+DVB_T+DVB_T2+TRICKMODE">' +
    '<ext><parentalcontrol schemes="dvb-si">true</parentalcontrol><clientMetadata type="dvb-si">true</clientMetadata></ext></ui_profile>' +
    '<audio_profile name="MPEG1_L3" type="audio/mpeg"/><audio_profile name="HEAAC" type="audio/mp4"/>' +
    '<video_profile name="MP4_AVC_SD_25_HEAAC" type="video/mp4"/><video_profile name="MP4_AVC_HD_25_HEAAC" type="video/mp4"/>' +
    '<video_profile name="MP4_AVC_SD_25_HEAAC" type="application/vnd.apple.mpegurl"/></profilelist>';

  // ---- Objects by type ----
  var state = new WeakMap();
  function st(o) { var s = state.get(o); if (!s) { s = { playState: 0, fullScreen: false }; state.set(o, s); } return s; }
  function type(o) { return String(o.getAttribute('type') || '').toLowerCase(); }
  function isBroadcast(o) { return type(o) === 'video/broadcast'; }
  var AV_TYPES = /^(video\/(mp4|mpeg4|mpeg|mp2t)|audio\/(mp4|mpeg)|application\/(dash\+xml|vnd\.apple\.mpegurl|x-mpegurl))$/;
  function isAV(o) { return AV_TYPES.test(type(o)); }

  function fire(o, name, props, args) {
    var e = document.createEvent('Event');
    e.initEvent(name, false, false);
    Object.keys(props || {}).forEach(function (k) { e[k] = props[k]; });
    try { o.dispatchEvent(e); } catch (x) {}
    var handler = o['on' + name];
    if (typeof handler === 'function') { try { handler.apply(o, args || []); } catch (x) { log('on' + name + ': ' + x); } }
  }
  function setPlayState(o, s, err) {
    st(o).playState = s;
    fire(o, 'PlayStateChange', { state: s, error: err }, [s, err]);
  }

  // A/V Control: playback via a <video> element inside the object.
  function video(o) {
    var s = st(o);
    if (!s.video) {
      var v = document.createElement('video');
      v.style.cssText = 'width:100%;height:100%;background:transparent';
      v.addEventListener('playing', function () { setPlayState(o, 1); });
      v.addEventListener('pause', function () { if (!v.ended && s.playState === 1) setPlayState(o, 2); });
      v.addEventListener('waiting', function () { setPlayState(o, 4); });
      v.addEventListener('ended', function () { setPlayState(o, 5); });
      v.addEventListener('error', function () { log('A/V error ' + (v.error && v.error.code)); setPlayState(o, 6, 0); });
      o.appendChild(v);
      s.video = v;
    }
    return s.video;
  }

  var methods = {
    // Application manager
    getOwnerApplication: function () { return app; },
    getApplicationVisualizationMode: function () { return 1; },
    // video/broadcast and A/V
    bindToCurrentChannel: function () { N.bindBroadcast(); setPlayState(this, 2); return currentChannel(); },
    setChannel: function (ch) {
      if (!ch) { N.stopBroadcast(); setPlayState(this, 0); return; }
      st(this).pending = true;
      N.setChannel(ch.onid | 0, ch.tsid | 0, ch.sid | 0);
    },
    prevChannel: function () { N.channelStep(-1); },
    nextChannel: function () { N.channelStep(1); },
    getChannelConfig: function () { return { channelList: channelList(), favouriteLists: [], currentFavouriteList: null }; },
    createChannelObject: function (idType, a, b, c) {
      if (typeof a === 'string') return { idType: idType, dsd: a, sid: b };
      return { idType: idType, onid: a, tsid: b, sid: c, ccid: 'ccid:dvbt.' + c };
    },
    getComponents: function () { return []; },
    getCurrentActiveComponents: function () { return []; },
    selectComponent: function () {}, unselectComponent: function () {},
    setFullScreen: function (f) { st(this).fullScreen = !!f; if (isBroadcast(this)) N.setFullScreen(!!f); fire(this, 'FullScreenChange', {}, []); },
    stop: function () {
      if (isBroadcast(this)) { N.stopBroadcast(); setPlayState(this, 3); return true; }
      var v = video(this); v.pause(); try { v.currentTime = 0; } catch (e) {} setPlayState(this, 0); return true;
    },
    release: function () {
      if (isBroadcast(this)) { N.releaseBroadcast(); setPlayState(this, 0); return; }
      var s = st(this); if (s.video) { s.video.pause(); s.video.removeAttribute('src'); s.video.load(); }
    },
    play: function (speed) {
      var v = video(this);
      var src = this.getAttribute('data') || this.data;
      if (src && v.getAttribute('src') !== src) { v.src = src; setPlayState(this, 3); }
      if (speed === 0) { v.pause(); setPlayState(this, 2); return true; }
      v.playbackRate = speed && speed > 0 ? speed : 1;
      v.play().catch(function (e) { log('A/V play: ' + e); });
      return true;
    },
    seek: function (ms) { video(this).currentTime = ms / 1000; return true; },
    setVolume: function (v) { video(this).volume = Math.max(0, Math.min(1, v / 100)); return true; },
    // Capabilities
    hasCapability: function (c) { return !/\+(DRM|PVR|ITV_KEYS)/.test(String(c)); }
  };
  Object.keys(methods).forEach(function (name) {
    if (HTMLObjectElement.prototype[name] === undefined) {
      Object.defineProperty(HTMLObjectElement.prototype, name, { value: methods[name], configurable: true, writable: true });
    }
  });

  var props = {
    currentChannel: function () { return isBroadcast(this) ? currentChannel() : undefined; },
    playState: function () { return st(this).playState; },
    fullScreen: function () { return st(this).fullScreen; },
    playPosition: function () { var s = st(this); return s.video ? Math.round(s.video.currentTime * 1000) : 0; },
    playTime: function () { var s = st(this); return s.video && isFinite(s.video.duration) ? Math.round(s.video.duration * 1000) : 0; },
    speed: function () { var s = st(this); return s.video && !s.video.paused ? s.video.playbackRate : 0; },
    error: function () { return -1; },
    configuration: function () {
      return {
        // The __DVBT_…__ markers are replaced by HbbTvBrowser (Region.hbbTvConfig): e.g. 'eng', 'en', 'GBR'.
        preferredAudioLanguage: '__DVBT_LANG3__', preferredAudioLanguage47: '__DVBT_LANG2__', preferredSubtitleLanguage: '__DVBT_LANG3__',
        preferredSubtitleLanguage47: '__DVBT_LANG2__', preferredUILanguage: '__DVBT_LANG3__', preferredUILanguage47: '__DVBT_LANG2__',
        countryId: '__DVBT_COUNTRY3__', regionId: 0, subtitlesEnabled: false, audioDescriptionEnabled: false,
        timeShiftSynchronized: false, dtt_network_ids: [], deviceId: 'baptv',
        getText: function () { return ''; }, setText: function () {}
      };
    },
    localSystem: function () {
      return {
        deviceID: 'baptv', modelName: 'bapTV', vendorName: 'Bonsaponsi', softwareVersion: N.version(),
        hardwareVersion: '1.0', serialNumber: '', releaseVersion: 1, majorVersion: 1, minorVersion: 5,
        oipfProfile: 'OITF_HD_UIPROF', pvrEnabled: false, ciplusEnabled: false, powerState: 1, volume: 100, mute: false
      };
    },
    xmlCapabilities: function () { return new DOMParser().parseFromString(capabilitiesXml, 'text/xml'); },
    extraSDVideoDecodes: function () { return 0; },
    extraHDVideoDecodes: function () { return 0; }
  };
  Object.keys(props).forEach(function (name) {
    if (!(name in HTMLObjectElement.prototype)) {
      Object.defineProperty(HTMLObjectElement.prototype, name, { get: props[name], configurable: true });
    }
  });

  // HbbTV assumes a 1280x720 screen.
  function fixViewport() {
    var metas = document.querySelectorAll('meta[name=viewport]');
    if (!metas.length && document.head) {
      var m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); metas = [m];
    }
    Array.prototype.forEach.call(metas, function (m) { m.setAttribute('content', 'width=1280, user-scalable=no'); });
  }
  document.addEventListener('DOMContentLoaded', fixViewport);
  if (document.head) fixViewport();

  // TV picture position: the video/broadcast object may be scaled down to a window.
  var lastRect = '';
  setInterval(function () {
    var o = document.querySelector('object[type="video/broadcast"]');
    var r = o ? o.getBoundingClientRect() : null;
    var visible = r && r.width > 0 && r.height > 0 && getComputedStyle(o).visibility !== 'hidden';
    var key = visible ? [r.left, r.top, r.width, r.height].map(Math.round).join(',') : 'full';
    if (key !== lastRect) { lastRect = key; N.broadcastRect(key); }
  }, 400);

  window.addEventListener('error', function (e) { log('JS error: ' + e.message + ' @' + e.filename + ':' + e.lineno); });

  // Diagnostics: failed network requests (request and response bodies) are written to the log.
  (function () {
    function cut(x) { return String(x === undefined ? '' : x).slice(0, 1500); }
    var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) { this.__dvbt = m + ' ' + u; return open.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function (body) {
      var x = this;
      x.addEventListener('loadend', function () {
        if (x.status === 0 || x.status >= 400) log('XHR ' + x.__dvbt + ' -> ' + x.status + ' request=' + cut(body) + ' response=' + cut(x.responseText));
      });
      return send.apply(this, arguments);
    };
    if (window.fetch) {
      var f = window.fetch;
      window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url);
        var body = init && init.body;
        return f.apply(this, arguments).then(function (r) {
          if (r.status >= 400) r.clone().text().then(function (t) { log('FETCH ' + url + ' -> ' + r.status + ' request=' + cut(body) + ' response=' + cut(t)); });
          return r;
        }, function (e) { log('FETCH ' + url + ' error: ' + e); throw e; });
      };
    }
  })();

  window.__dvbtHbb = {
    key: sendKey,
    channelChanged: function () {
      Array.prototype.forEach.call(document.querySelectorAll('object[type="video/broadcast"]'), function (o) {
        var ch = currentChannel();
        st(o).pending = false;
        fire(o, 'ChannelChangeSucceeded', { channel: ch }, [ch]);
        setPlayState(o, 2);
      });
    },
    keyset: keyset
  };
  log('HbbTV layer ready: ' + location.href);
})();
