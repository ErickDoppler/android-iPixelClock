/* iPixel Clock web interface.
 *
 * Plain ES5 on purpose. This same page is loaded by the WebView inside the
 * Android app, which on a never-updated Android 5 device is roughly Chrome 37 —
 * no arrow functions, no const/let, no template literals, no fetch, no
 * Object.assign. XMLHttpRequest and WebSocket are both fine there.
 *
 * The page is data-driven: the control lists come from GET /api/schema, so a
 * font or an effect added in Kotlin shows up here with no HTML change, and an
 * option is never offered that will not fit the attached panel.
 */
(function () {
  'use strict';

  var state = null;
  var schema = null;
  var socket = null;
  var reconnectDelay = 500;
  var suppress = false;      // set while writing values into inputs
  var dismissedReminder = false;
  var frames = 0, fpsAt = 0, fps = 0;

  var embedded = location.search.indexOf('embedded=1') >= 0;

  var $ = function (id) { return document.getElementById(id); };

  // ------------------------------------------------------------------ boot

  function boot() {
    if (embedded) {
      // The Android app draws a better preview natively just above this view.
      var block = $('preview-block');
      if (block) block.className = 'preview-block hidden';
      var lo = $('logout');
      if (lo) lo.style.display = 'none';
    }
    wireTabs();
    wireControls();
    wireCitySearch();
    getJson('/api/schema', function (err, data) {
      if (!err) { schema = data; buildOptions(); }
      connect();
    });
  }

  // ------------------------------------------------------------------ tabs

  function wireTabs() {
    var tabs = document.querySelectorAll('.tab');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].onclick = function () {
        var name = this.getAttribute('data-tab');
        var all = document.querySelectorAll('.tab');
        for (var j = 0; j < all.length; j++) {
          all[j].className = all[j] === this ? 'tab active' : 'tab';
        }
        var pages = document.querySelectorAll('.page');
        for (var k = 0; k < pages.length; k++) {
          pages[k].className = (pages[k].id === 'page-' + name) ? 'page active' : 'page';
        }
      };
    }
  }

  // ---------------------------------------------------------- the socket

  function connect() {
    var proto = (location.protocol === 'https:') ? 'wss://' : 'ws://';
    try {
      socket = new WebSocket(proto + location.host + '/ws');
    } catch (e) {
      setConn('bad', 'NO SOCKET');
      return;
    }
    socket.binaryType = 'arraybuffer';

    socket.onopen = function () {
      reconnectDelay = 500;
      setConn('ok', 'LINKED');
    };
    socket.onmessage = function (ev) {
      if (typeof ev.data === 'string') {
        try { apply(JSON.parse(ev.data)); } catch (e) { /* ignore */ }
      } else {
        drawFrame(new Uint8Array(ev.data));
      }
    };
    socket.onclose = function () {
      setConn('bad', 'LINK LOST — RETRYING');
      socket = null;
      setTimeout(connect, reconnectDelay);
      // Back off, but never so far that a phone waking from doze looks dead.
      reconnectDelay = Math.min(reconnectDelay * 2, 8000);
    };
    socket.onerror = function () { /* onclose does the work */ };
  }

  function send(op, data) {
    if (!socket || socket.readyState !== 1) {
      // No socket: fall back to a normal POST so the control still works.
      postJson('/api/' + (op === 'settings' ? 'settings' : op), data, function () {});
      return;
    }
    socket.send(JSON.stringify({ op: op, data: data }));
  }

  // --------------------------------------------------------- the preview

  function drawFrame(bytes) {
    if (embedded) return;
    if (bytes.length < 5 || bytes[0] !== 1) return;
    var w = (bytes[1] << 8) | bytes[2];
    var h = (bytes[3] << 8) | bytes[4];
    if (w <= 0 || h <= 0 || bytes.length < 5 + w * h * 3) return;

    var canvas = $('preview');
    // Draw at panel resolution and let CSS blow it up; the browser's own
    // nearest-neighbour scaling is what gives the pixels their hard edges.
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
      var shell = canvas.parentNode;
      if (h > w) {
        // A vertical banner: bound it by height and let the width follow, and
        // let the frame shrink to it, or a 16x144 panel is a thin line adrift
        // in a page-wide box.
        canvas.style.width = 'auto';
        canvas.style.height = '320px';
        canvas.style.maxWidth = '100%';
        if (shell) shell.className = 'preview-shell portrait';
      } else {
        canvas.style.width = '100%';
        canvas.style.height = 'auto';
        canvas.style.maxHeight = '240px';
        if (shell) shell.className = 'preview-shell';
      }
    }
    var ctx = canvas.getContext('2d');
    var img = ctx.createImageData(w, h);
    var src = 5, dst = 0;
    for (var i = 0; i < w * h; i++) {
      img.data[dst++] = bytes[src++];
      img.data[dst++] = bytes[src++];
      img.data[dst++] = bytes[src++];
      img.data[dst++] = 255;
    }
    ctx.putImageData(img, 0, 0);

    frames++;
    var now = Date.now();
    if (!fpsAt) fpsAt = now;
    if (now - fpsAt >= 2000) {
      fps = Math.round(frames * 1000 / (now - fpsAt) * 10) / 10;
      frames = 0;
      fpsAt = now;
      var el = $('panel-fps');
      if (el) el.innerHTML = fps + ' fps';
    }
  }

  // ---------------------------------------------------------- the schema

  function buildOptions() {
    if (!schema) return;
    fillSelect('fontFamily', schema.fonts, 'id', 'name');
    fillSelect('colorMode', schema.colorModes, 'id', 'label');
    fillSelect('transition', schema.transitions, 'id', 'label');
    fillSelect('background', schema.backgrounds, 'id', 'label');
    fillSelect('messageEffect', schema.messageEffects, 'id', 'label');
    fillSelect('visibility', schema.visibilities, 'id', 'label');
    fillSelect('dateFormat', schema.dateFormats, 'id', 'label');
    fillSelect('verticalStyle', schema.verticalStyles, 'id', 'label');

    var sim = $('simSize');
    sim.innerHTML = '';
    for (var i = 0; i < schema.panelPresets.length; i++) {
      var p = schema.panelPresets[i];
      var o = document.createElement('option');
      o.value = p.w + 'x' + p.h;
      o.innerHTML = p.label;
      sim.appendChild(o);
    }
  }

  function fillSelect(id, list, valueKey, labelKey) {
    var el = $(id);
    if (!el || !list) return;
    el.innerHTML = '';
    for (var i = 0; i < list.length; i++) {
      var o = document.createElement('option');
      o.value = list[i][valueKey];
      o.innerHTML = list[i][labelKey];
      el.appendChild(o);
    }
  }

  // -------------------------------------------------------- the controls

  // Every plain setting: id in the DOM == key in the settings JSON.
  var CHECKS = ['displayOn', 'brightnessAuto', 'mirrorH', 'mirrorV', 'hour24', 'metricUnits',
    'showSeconds', 'blinkColon', 'leadingZero', 'showDate', 'showWeather',
    'showSunrise', 'showSunset', 'startOnBoot',
    'messageEnabled', 'messageSchedule', 'messageCustomColor', 'messageCustomBackground'];

  var RANGES = ['brightness', 'brightnessDay', 'brightnessNight', 'gradientAngle',
    'colorSpeed', 'transitionMs', 'backgroundSpeed', 'backgroundIntensity',
    'frameIntervalMs', 'messageSpeed'];

  var SELECTS = ['fontFamily', 'colorMode', 'transition', 'background',
    'visibility', 'dateFormat', 'verticalStyle', 'messageEffect'];

  var NUMBERS = ['dutyShowSeconds', 'hideSeconds', 'port',
    'infoTimeSeconds', 'infoDateSeconds', 'infoConditionsSeconds', 'infoTelemetrySeconds',
    'messageRepeat', 'messageEveryMinutes'];

  var COLORS = ['colorPrimary', 'colorSecondary', 'backgroundColor', 'backgroundColor2',
    'messageColor', 'messageBackground'];

  var TEXTS = ['cityName', 'messageText'];

  function wireControls() {
    var i;

    for (i = 0; i < CHECKS.length; i++) bindCheck(CHECKS[i]);
    for (i = 0; i < RANGES.length; i++) bindRange(RANGES[i]);
    for (i = 0; i < SELECTS.length; i++) bindSelect(SELECTS[i]);
    for (i = 0; i < NUMBERS.length; i++) bindNumber(NUMBERS[i]);
    for (i = 0; i < COLORS.length; i++) bindColor(COLORS[i]);
    for (i = 0; i < TEXTS.length; i++) bindText(TEXTS[i]);

    var rots = document.querySelectorAll('.rot');
    for (i = 0; i < rots.length; i++) {
      rots[i].onclick = function () {
        patch({ rotation: parseInt(this.getAttribute('data-rot'), 10) });
      };
    }

    $('detect').onclick = function () { postJson('/api/detect', { action: 'scan' }, noop); };
    $('forget').onclick = function () { postJson('/api/detect', { action: 'forget' }, noop); };
    $('retune').onclick = function () { postJson('/api/detect', { action: 'retune' }, noop); };

    $('simSize').onchange = function () {
      var parts = this.value.split('x');
      patch({
        simulatedWidth: parseInt(parts[0], 10),
        simulatedHeight: parseInt(parts[1], 10)
      });
    };

    $('message-show').onclick = function () {
      // The text box may not have blurred yet — on a phone keyboard it usually
      // has not — so send what is in it rather than what was last committed.
      postJson('/api/message', { action: 'show', settings: messagePatch() }, noop);
    };
    $('message-stop').onclick = function () {
      postJson('/api/message', { action: 'cancel' }, noop);
    };

    $('reminder-detect').onclick = function () {
      postJson('/api/detect', { action: 'scan' }, noop);
    };
    $('reminder-dismiss').onclick = function () {
      dismissedReminder = true;
      $('reminder').className = 'reminder hidden';
    };

    var logout = $('logout');
    if (logout) {
      logout.onclick = function () {
        postJson('/api/logout', {}, function () { location.reload(); });
      };
    }
  }

  function bindCheck(id) {
    var el = $(id);
    if (!el) return;
    el.onchange = function () {
      if (suppress) return;
      var p = {}; p[id] = el.checked; patch(p);
      // Locally, rather than waiting for the state push to come back: a row
      // that appears a round trip after the switch feels broken.
      if (id === 'messageSchedule') showMessageRows(el.checked);
      if (id === 'messageCustomColor') showSubRow('messageColor', el.checked);
      if (id === 'messageCustomBackground') showSubRow('messageBackground', el.checked);
    };
  }

  function bindRange(id) {
    var el = $(id);
    if (!el) return;
    var out = $(id + '-out');
    // Live feedback on every pixel of travel, but only one message per frame
    // of drag: a slider dragged across a phone fires oninput dozens of times.
    var pending = null;
    el.oninput = function () {
      if (out) out.innerHTML = el.value;
      if (suppress) return;
      pending = parseInt(el.value, 10);
      if (!el._timer) {
        el._timer = setTimeout(function () {
          el._timer = null;
          var p = {}; p[id] = pending; patch(p);
        }, 60);
      }
    };
    el.onchange = el.oninput;
  }

  function bindSelect(id) {
    var el = $(id);
    if (!el) return;
    el.onchange = function () {
      if (suppress) return;
      var p = {}; p[id] = el.value; patch(p);
      if (id === 'fontFamily') showFontBlurb(el.value);
      if (id === 'visibility') showVisibilityRows(el.value);
      if (id === 'messageEffect') showEffectNote(el.value);
    };
  }

  function bindNumber(id) {
    var el = $(id);
    if (!el) return;
    el.onchange = function () {
      if (suppress) return;
      var v = parseInt(el.value, 10);
      if (isNaN(v)) return;
      var p = {}; p[id] = v; patch(p);
    };
  }

  function bindColor(id) {
    var el = $(id);
    if (!el) return;
    el.onchange = function () {
      if (suppress) return;
      var p = {}; p[id] = el.value; patch(p);   // "#rrggbb"; the server parses it
    };
  }

  function bindText(id) {
    var el = $(id);
    if (!el) return;
    el.onchange = function () {
      if (suppress) return;
      var p = {}; p[id] = el.value; patch(p);
    };
  }

  function patch(obj) { send('settings', obj); }

  // ------------------------------------------------------------ the state

  function apply(s) {
    if (!s || !s.settings) return;
    state = s;
    suppress = true;

    var set = s.settings;
    var i;

    for (i = 0; i < CHECKS.length; i++) {
      var c = $(CHECKS[i]);
      if (c) c.checked = !!set[CHECKS[i]];
    }
    for (i = 0; i < RANGES.length; i++) {
      var r = $(RANGES[i]);
      if (r && document.activeElement !== r) {
        r.value = set[RANGES[i]];
        var o = $(RANGES[i] + '-out');
        if (o) o.innerHTML = set[RANGES[i]];
      }
    }
    for (i = 0; i < SELECTS.length; i++) {
      var sel = $(SELECTS[i]);
      if (sel) sel.value = set[SELECTS[i]];
    }
    for (i = 0; i < NUMBERS.length; i++) {
      var n = $(NUMBERS[i]);
      if (n && document.activeElement !== n) n.value = set[NUMBERS[i]];
    }
    for (i = 0; i < COLORS.length; i++) {
      var col = $(COLORS[i]);
      if (col) col.value = toHex(set[COLORS[i]]);
    }
    for (i = 0; i < TEXTS.length; i++) {
      var t = $(TEXTS[i]);
      if (t && document.activeElement !== t) t.value = set[TEXTS[i]] || '';
    }

    // Rotation buttons and the little turning panel figure.
    var rots = document.querySelectorAll('.rot');
    for (i = 0; i < rots.length; i++) {
      var deg = parseInt(rots[i].getAttribute('data-rot'), 10);
      rots[i].className = (deg === set.rotation) ? 'rot active' : 'rot';
    }
    var fig = $('orient-panel');
    if (fig) {
      var tr = 'rotate(' + set.rotation + 'deg)' +
        (set.mirrorH ? ' scaleX(-1)' : '') + (set.mirrorV ? ' scaleY(-1)' : '');
      fig.style.webkitTransform = tr;
      fig.style.transform = tr;
    }

    showFontBlurb(set.fontFamily);
    showVisibilityRows(set.visibility);
    showMessageRows(set.messageSchedule);
    showSubRow('messageColor', set.messageCustomColor);
    showSubRow('messageBackground', set.messageCustomBackground);
    showEffectNote(set.messageEffect);
    showMessagePlan(s.message);

    var sim = $('simSize');
    if (sim) sim.value = set.simulatedWidth + 'x' + set.simulatedHeight;

    applyPanel(s.panel || {});
    applyServer(s.server || {});
    applyTransport(s.transport || {}, s.fps);
    applyReadouts(s.readouts);

    suppress = false;
  }

  function applyPanel(p) {
    var cap = $('panel-caption');
    if (cap) {
      // The size quoted is the panel's own, which is how the firmware reports
      // it and what is printed on the box. When it is mounted turned, say so —
      // otherwise "96x16" above a tall preview just looks wrong.
      var rot = (state && state.settings) ? state.settings.rotation : 0;
      cap.innerHTML = (p.live ? 'LIVE ' : 'SIMULATED ') + p.width + '×' + p.height +
        (rot ? ' · mounted ' + rot + '°' : '');
      cap.className = p.live ? 'caption-main' : 'caption-main sim';
    }
    setText('st-state', p.status || (p.live ? 'connected' : 'no panel'),
      p.live ? 'good' : 'warn');
    setText('st-size', p.width + '×' + p.height, '');
    setText('st-bt', p.bluetoothOn ? 'on' : 'off', p.bluetoothOn ? 'good' : 'warn');

    var rem = $('reminder');
    if (rem) {
      // The Android shell prints the same reminder in its own header, right
      // above this WebView, so showing it twice would just be shouting.
      if (p.reminder && !dismissedReminder && !embedded) {
        $('reminder-text').innerHTML = p.reminder;
        rem.className = 'reminder';
      } else {
        rem.className = 'reminder hidden';
      }
    }
  }

  function applyServer(sv) {
    var ip = (sv.ips && sv.ips.length) ? sv.ips[0] : null;
    setText('st-url', ip ? ('http://' + ip + ':' + sv.port) : 'no network', ip ? 'good' : 'warn');
    setText('st-port', String(sv.port || '—'), '');
    setText('st-pw', sv.passwordSet ? 'set' : 'NOT SET — set it in the app',
      sv.passwordSet ? 'good' : 'bad');
    var v = $('viewers');
    if (v) v.innerHTML = (sv.viewers || 0) + ' watching';
  }

  var FRAME_MODES = ['PNG over 0x0002', 'raw RGB over 0x0002', 'live 0x0000'];

  function applyTransport(t, serverFps) {
    // The server's count is authoritative: it counts frames the panel actually
    // took, whereas the socket's own fps is capped by the preview rate.
    var f = (typeof serverFps === 'number') ? serverFps : 0;
    setText('st-fps', f + ' fps', f >= 6 ? 'good' : (f > 0 ? 'warn' : ''));
    // The render rate should comfortably exceed the panel's. If it does not,
    // the phone is the bottleneck rather than the display, which is worth
    // being able to see at a glance.
    var r = (state && typeof state.renderFps === 'number') ? state.renderFps : 0;
    setText('st-render', r + ' fps', (r > f * 1.5) ? 'good' : 'warn');
    setText('st-mode', FRAME_MODES[t.frameMode] || String(t.frameMode), '');
    setText('st-tuning', t.tuning || 'not measured yet', '');
    setText('st-chunk', String(t.chunkMax || '—'), '');
    setText('st-phy', t.phy2m ? 'requested' : 'off', '');
    setText('st-floor', (t.minIntervalMs || 0) + ' ms', '');
  }

  var PAGE_NAMES = {
    TIME: 'the time', DATE: 'the date',
    CONDITIONS: 'conditions', TELEMETRY: 'pressure / humidity'
  };

  function applyReadouts(r) {
    if (!r) return;
    setText('st-page', PAGE_NAMES[r.page] || r.page, 'good');

    if (r.weather) {
      setText('st-weather',
        Math.round(r.weather.tempC) + '°C, ' + r.weather.description, 'good');
    } else {
      setText('st-weather',
        r.hasLocation ? 'no reading yet' : 'needs a location', 'warn');
    }

    if (r.hasTelemetry) {
      var bits = [];
      if (r.pressureHpa !== null) bits.push(Math.round(r.pressureHpa) + ' hPa');
      if (r.humidity !== null) bits.push(r.humidity + '% RH');
      // Worth saying which: a barometer measures this room, the forecast
      // measures the region, and they are not the same claim.
      bits.push(r.barometer ? '(this phone)' : '(forecast)');
      setText('st-telemetry', bits.join('  '), 'good');
    } else {
      setText('st-telemetry', 'none — conditions get 10s instead', 'warn');
    }

    var set = state && state.settings ? state.settings : null;
    if (set) {
      setText('st-city', set.cityName ? set.cityName : "the phone's location",
        set.cityName ? 'good' : '');
    }
    setText('st-pos', r.hasLocation
      ? (r.lat.toFixed(3) + ', ' + r.lon.toFixed(3))
      : 'unknown', r.hasLocation ? 'good' : 'warn');

    if (set) {
      // Always the sum of all four, whatever is missing. 60 is the value that
      // keeps the rotation landing on the same seconds every minute.
      var cycle = (set.infoTimeSeconds || 0) + (set.infoDateSeconds || 0) +
        (set.infoConditionsSeconds || 0) + (set.infoTelemetrySeconds || 0);
      setText('st-cycle',
        cycle + ' s' + (cycle === 60 ? ' — locked to the minute' : ' — drifts across the minute'),
        cycle === 60 ? 'good' : 'warn');
    }
  }

  function wireCitySearch() {
    var box = $('citySearch');
    var out = $('city-results');
    if (!box || !out) return;

    box.onkeydown = function (e) {
      if (e.keyCode !== 13) return;
      var q = box.value;
      if (!q) return;
      out.innerHTML = '<span class="hint">searching…</span>';
      getJson('/api/geocode?q=' + encodeURIComponent(q), function (err, data) {
        if (err || !data || !data.results || !data.results.length) {
          out.innerHTML = '<span class="hint">nothing found</span>';
          return;
        }
        out.innerHTML = '';
        for (var i = 0; i < data.results.length; i++) {
          out.appendChild(cityRow(data.results[i]));
        }
      });
    };

    $('city-clear').onclick = function () {
      // Null clears the stored coordinates; the hub falls back to the fix.
      patch({ cityName: '', cityLat: null, cityLon: null });
      out.innerHTML = '';
      box.value = '';
    };
  }

  function cityRow(p) {
    var b = document.createElement('button');
    b.className = 'ghost city';
    b.innerHTML = p.name + (p.country ? ('  ' + p.country) : '');
    b.onclick = function () {
      patch({ cityName: p.name, cityLat: p.lat, cityLon: p.lon });
      $('city-results').innerHTML = '';
      $('citySearch').value = '';
    };
    return b;
  }

  function showFontBlurb(id) {
    var el = $('font-blurb');
    if (!el || !schema || !schema.fonts) return;
    for (var i = 0; i < schema.fonts.length; i++) {
      if (schema.fonts[i].id === id) {
        el.innerHTML = schema.fonts[i].blurb +
          ' Authored at ' + schema.fonts[i].heights.join(', ') + ' rows.';
        return;
      }
    }
    el.innerHTML = '';
  }

  /** What SHOW NOW sends alongside the command: the box as it stands. */
  function messagePatch() {
    var p = {};
    var t = $('messageText');
    var n = $('messageRepeat');
    if (t) p.messageText = t.value;
    if (n && !isNaN(parseInt(n.value, 10))) p.messageRepeat = parseInt(n.value, 10);
    return p;
  }

  function showMessageRows(on) {
    var row = $('row-messageEvery');
    if (row) row.className = on ? 'row sub' : 'row sub hidden';
  }

  /** A colour picker is only shown when its Custom switch is on. */
  function showSubRow(id, on) {
    var row = $('row-' + id);
    if (row) row.className = on ? 'row sub' : 'row sub hidden';
  }

  /** Whether the chosen effect scrolls the text or reveals it in place. */
  function showEffectNote(id) {
    var el = $('message-effect-note');
    if (!el || !schema || !schema.messageEffects) return;
    // The ones that carry the text across the panel. The vertical pair are not
    // among them: rolling a line up a 16-row strip does nothing about its being
    // too wide, so those page like the rest.
    var travelling = { 'scroll-left': 1, 'scroll-right': 1, 'wave': 1, 'ticker': 1 };
    el.innerHTML = travelling[id]
      ? 'This one travels, so the text can be any length and speed is how fast it moves.'
      : 'This one reveals the text in place. Anything too wide for the panel is ' +
        'broken into pages shown in turn, and speed is how long each page holds.';
  }

  function showMessagePlan(m) {
    var el = $('message-pages');
    if (!el) return;
    if (!m || !m.pages) {
      el.innerHTML = 'No text set — nothing will be shown.';
      return;
    }
    var secs = Math.round(m.totalMs / 100) / 10;
    el.innerHTML = (m.running ? '<b>Showing now.</b> ' : '') +
      m.pages + (m.pages === 1 ? ' page' : ' pages') +
      ', one showing takes ' + secs + ' s.';
  }

  function showVisibilityRows(mode) {
    var show = (mode === 'duty');
    var a = $('dutyShowSeconds'), b = $('hideSeconds');
    if (a && a.parentNode) a.parentNode.className = show ? 'row sub' : 'row sub hidden';
    if (b && b.parentNode) b.parentNode.className = show ? 'row sub' : 'row sub hidden';
  }

  // ----------------------------------------------------------------- utils

  function setText(id, text, cls) {
    var el = $(id);
    if (!el) return;
    el.innerHTML = text;
    el.className = 'v' + (cls ? ' ' + cls : '');
  }

  function setConn(cls, text) {
    var el = $('conn');
    if (!el) return;
    el.className = 'conn ' + cls;
    el.innerHTML = text;
  }

  function toHex(v) {
    if (typeof v !== 'number') return '#000000';
    var rgb = (v & 0xFFFFFF).toString(16);
    while (rgb.length < 6) rgb = '0' + rgb;
    return '#' + rgb;
  }

  function noop() {}

  function getJson(url, cb) {
    var x = new XMLHttpRequest();
    x.open('GET', url, true);
    x.onreadystatechange = function () {
      if (x.readyState !== 4) return;
      if (x.status >= 200 && x.status < 300) {
        try { cb(null, JSON.parse(x.responseText)); }
        catch (e) { cb(e, null); }
      } else {
        cb(new Error('HTTP ' + x.status), null);
      }
    };
    x.send();
  }

  function postJson(url, body, cb) {
    var x = new XMLHttpRequest();
    x.open('POST', url, true);
    x.setRequestHeader('Content-Type', 'application/json');
    x.onreadystatechange = function () {
      if (x.readyState !== 4) return;
      if (x.status === 401) { location.reload(); return; }
      try { cb(null, JSON.parse(x.responseText)); } catch (e) { cb(e, null); }
    };
    x.send(JSON.stringify(body || {}));
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
