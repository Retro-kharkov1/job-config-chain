/*
 * Script shared by all three plugin pages (Config Sets list, job-level Config Chains page, common
 * Config Set editor): table filtering (tableToolsBlock tag) and the confirm-by-name dialog gate
 * (confirmByNameBlock tag). Pulled in by each page's own script adjunct via an include directive,
 * so it is emitted once per page, before the page-specific script. CSP-clean: no inline script or
 * handlers anywhere in the plugin's pages.
 */

/* ---- Show/hide through a class, never an inline style (CSP-clean, one convention) ---- */
var CTSYNC_HIDDEN_CLASS = 'ctsync-hidden';
function ctsyncSetHidden(el, hidden) {
  if (!el) { return; }
  if (hidden) { el.classList.add(CTSYNC_HIDDEN_CLASS); } else { el.classList.remove(CTSYNC_HIDDEN_CLASS); }
}
function ctsyncToggleHidden(el) {
  if (!el) { return; }
  ctsyncSetHidden(el, !el.classList.contains(CTSYNC_HIDDEN_CLASS));
}

/* ---- Monaco web workers from same-origin URLs (CSP) ----
   The bundled Monaco builds each worker from a blob: URL (a Blob wrapper that importScripts the real
   worker file), which Jenkins' enforced Content-Security-Policy refuses: worker-src falls back to
   child-src, then default-src 'self', and 'self' does not cover blob:. The real worker files are
   plain classic scripts served by this plugin under <monacoBase>/assets/, so after editor.main has
   installed its own MonacoEnvironment this replaces getWorker with one that starts them directly by
   URL. That is same-origin and needs no change to the CSP at all. Worker file names carry the
   bundle's content hashes; MonacoWorkerAssetsTest fails if the bundle is upgraded and this map is
   not. No label-specific worker is ever requested for XML/YAML (plain editor worker). */
var CTSYNC_MONACO_WORKERS = {
  json: 'assets/json.worker-CoJx_OPf.js',
  css: 'assets/css.worker-URu8fCFR.js',
  scss: 'assets/css.worker-URu8fCFR.js',
  less: 'assets/css.worker-URu8fCFR.js',
  html: 'assets/html.worker-D1SL3iM8.js',
  handlebars: 'assets/html.worker-D1SL3iM8.js',
  razor: 'assets/html.worker-D1SL3iM8.js',
  typescript: 'assets/ts.worker-BWKtMYOk.js',
  javascript: 'assets/ts.worker-BWKtMYOk.js'
};
var CTSYNC_MONACO_DEFAULT_WORKER = 'assets/editor.worker-lj3bdIIn.js';
function ctsyncInstallMonacoWorkers(monacoBase) {
  var base = String(monacoBase || '').replace(/\/+$/, '');
  var env = self.MonacoEnvironment || {};
  env.getWorker = function (moduleId, label) {
    var file = CTSYNC_MONACO_WORKERS[label] || CTSYNC_MONACO_DEFAULT_WORKER;
    return new Worker(base + '/' + file, { name: label });
  };
  self.MonacoEnvironment = env;
}

/* ---- Table filtering (tableToolsBlock tag) ---- */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncTableToolsStrings');
    return {
      placeholder: (tpl && tpl.dataset.filterPlaceholder) || 'Filter',
      count: (tpl && tpl.dataset.countLabel) || '{0} of {1}',
      noMatches: (tpl && tpl.dataset.noMatches) || '',
      any: (tpl && tpl.dataset.anyLabel) || 'Any'
    };
  }

  function format(template, a, b) {
    return template.replace('{0}', String(a)).replace('{1}', String(b));
  }

  function dataRows(tbody) {
    return Array.prototype.filter.call(tbody.rows, function (tr) {
      return !tr.classList.contains('ctsync-empty-state-row');
    });
  }

  function whenSortingWired(table, callback) {
    if (!table.classList.contains('sortable') || table.sortable) { callback(); return; }
    var attempts = 0;
    (function poll() {
      if (table.sortable || attempts++ > 50) { callback(); return; }
      setTimeout(poll, 20);
    })();
  }

  function buildControl(th, s) {
    var enumerable = th.dataset.ctsyncFilter === 'enum';
    var control;
    if (enumerable) {
      control = document.createElement('select');
      control.className = 'jenkins-select__input ctsync-filter-input';
    } else {
      control = document.createElement('input');
      control.type = 'search';
      control.className = 'jenkins-input ctsync-filter-input';
      control.placeholder = s.placeholder;
    }
    return { control: control, enumerable: enumerable };
  }

  function wire(table) {
    var thead = table.tHead;
    var tbody = table.tBodies[0];
    if (!thead || !tbody || table.dataset.ctsyncFilterWired === 'true') { return; }
    table.dataset.ctsyncFilterWired = 'true';

    var s = strings();
    var inputs = [];

    Array.prototype.forEach.call(thead.rows[0].cells, function (th, index) {
      var caption = th.textContent.trim();
      if (caption === '' || th.dataset.sortDisable) { return; }

      var built = buildControl(th, s);
      var control = built.control;
      control.setAttribute('aria-label', s.placeholder + ': ' + caption);
      control.addEventListener('click', function (e) { e.stopPropagation(); });
      control.addEventListener('mousedown', function (e) { e.stopPropagation(); });
      control.addEventListener(built.enumerable ? 'change' : 'input', function () {
        apply(table);
      });
      th.appendChild(control);
      inputs.push({ index: index, input: control, enumerable: built.enumerable });
    });

    table.ctsyncFilterInputs = inputs;
    syncEnumOptions(table);

    var status = document.createElement('div');
    status.className = 'ctsync-filter-status';
    status.hidden = true;
    var scrollWrap = table.closest ? table.closest('.ctsync-scroll-table') : null;
    var anchor = scrollWrap || table;
    anchor.parentNode.insertBefore(status, anchor.nextSibling);
    table.ctsyncFilterStatus = status;

    if (typeof window !== 'undefined' && window.MutationObserver) {
      new MutationObserver(function () {
        syncEnumOptions(table);
        apply(table);
      }).observe(tbody, { childList: true });
    }
  }

  function syncEnumOptions(table) {
    var s = strings();
    (table.ctsyncFilterInputs || []).forEach(function (f) {
      if (!f.enumerable) { return; }
      var previous = f.input.value;
      var seen = [];
      dataRows(table.tBodies[0]).forEach(function (tr) {
        var cell = tr.cells[f.index];
        var text = cell ? cell.textContent.trim() : '';
        if (text !== '' && seen.indexOf(text) === -1) { seen.push(text); }
      });
      seen.sort();
      f.input.innerHTML = '';
      var any = document.createElement('option');
      any.value = '';
      any.text = s.any;
      f.input.appendChild(any);
      seen.forEach(function (value) {
        var option = document.createElement('option');
        option.value = value;
        option.text = value;
        f.input.appendChild(option);
      });
      f.input.value = seen.indexOf(previous) !== -1 ? previous : '';
    });
  }

  function apply(table) {
    var s = strings();
    var terms = (table.ctsyncFilterInputs || [])
        .map(function (f) {
          return { index: f.index, enumerable: f.enumerable,
                   value: f.input.value.trim().toLowerCase() };
        })
        .filter(function (f) { return f.value !== ''; });

    var rows = dataRows(table.tBodies[0]);
    var visible = 0;
    rows.forEach(function (tr) {
      var match = terms.every(function (t) {
        var cell = tr.cells[t.index];
        if (!cell) { return false; }
        var text = cell.textContent.trim().toLowerCase();
        return t.enumerable ? text === t.value : text.indexOf(t.value) !== -1;
      });
      tr.style.display = match ? '' : 'none';
      if (match) { visible++; }
    });

    var status = table.ctsyncFilterStatus;
    if (status) {
      status.hidden = terms.length === 0;
      status.textContent = (terms.length > 0 && visible === 0 && rows.length > 0)
          ? s.noMatches
          : format(s.count, visible, rows.length);
    }
  }

  function init() {
    Array.prototype.forEach.call(document.querySelectorAll('table.ctsync-filterable'),
      function (table) {
        whenSortingWired(table, function () { wire(table); });
      });
  }

  var refreshAll = function () {
    Array.prototype.forEach.call(document.querySelectorAll('table.ctsync-filterable'),
      function (table) {
        if (table.ctsyncFilterInputs) {
          syncEnumOptions(table);
          apply(table);
        }
      });
  };
  if (typeof window !== 'undefined') {
    window.ctsyncRefreshTableFilters = refreshAll;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

/* ---- Confirm-by-name dialog gate (confirmByNameBlock tag) ---- */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncConfirmStrings');
    return {
      cancel: (tpl && tpl.dataset.cancelLabel) || 'Cancel',
      mismatch: (tpl && tpl.dataset.nameMismatch) || ''
    };
  }

  function hasDialog() {
    return typeof window !== 'undefined' && window.dialog
        && typeof window.dialog.prompt === 'function';
  }

  function format(template) {
    var args = arguments;
    return String(template).replace(/\{(\d+)\}/g, function (placeholder, index) {
      var value = args[Number(index) + 1];
      return value === undefined ? placeholder : String(value);
    });
  }

  function confirmByName(opts) {
    var s = strings();
    function proceed(typed) {
      if (typed === null || typed === undefined) { return; }
      if (typed.replace(/^\s+|\s+$/g, '') !== opts.expectedName) {
        notify(s.mismatch, true);
        return;
      }
      opts.onConfirm(typed);
    }
    if (!hasDialog()) {
      proceed(window.prompt(opts.message));
      return;
    }
    var promptResult = window.dialog.prompt(opts.title, {
      message: opts.message,
      allowEmpty: false,
      type: 'destructive',
      okText: opts.okLabel,
      cancelText: s.cancel
    });
    // Bug fix (2026-09-28): core's own dialog only gates its OK button on "is the field
    // non-empty" (allowEmpty:false) — it has no notion of the exact name this dialog requires, so
    // the button used to become clickable on ANY typed text, not just a match. proceed() above
    // already refused a mismatch (it never called opts.onConfirm), so this was never a
    // data-safety hole, but it broke the disabled-until-correct promise the button visually
    // makes: a mismatched Enter/click closed the dialog and only then reported the mismatch,
    // instead of never being clickable in the first place. dialog.prompt() renders (and shows)
    // its dialog synchronously before returning the pending promise above — the same reason the
    // dialog is already visible to the operator the instant this call returns — so the freshly
    // opened <dialog> is reachable immediately afterwards, with no need to wait on anything async.
    enforceNameMatchOnOpenDialog(opts.expectedName, s.cancel);
    promptResult.then(proceed).catch(function () {
      // Cancelling rejects with no argument — swallow to avoid an unhandled-rejection console error.
    });
  }

  // Bug fix (2026-09-28), see confirmByName's own comment above for the "why". This codebase never
  // has more than one of these modal prompts open at a time, so the freshly opened dialog is
  // always the LAST <dialog> element in the document at this point.
  function enforceNameMatchOnOpenDialog(expectedName, cancelLabel) {
    if (typeof document === 'undefined' || !document.querySelectorAll) { return; }
    var dialogs = document.querySelectorAll('dialog');
    var dlg = dialogs[dialogs.length - 1];
    if (!dlg) { return; }
    wireDialogNameInput(dlg, expectedName, cancelLabel);
  }

  function findDialogOkButton(dlg, cancelLabel) {
    // Verified against a live Jenkins 2.568.3 controller (2026-09-28): core's dialog.prompt()
    // never renders a "...--destructive" class - the OK button's actual class string is
    // 'jenkins-button jenkins-button--primary jenkins-!-destructive-color' even for
    // type:'destructive', and that combination is theming, not addressing (it can change with
    // dialog type or a future skin). What core DOES render consistently is a data-id attribute
    // pairing each control with its role - data-id="ok", data-id="cancel", data-id="input" - so
    // that is the hook used here.
    var byDataId = dlg.querySelector('button[data-id="ok"]');
    if (byDataId) { return byDataId; }
    // Defensive fallback only: not known to be exercised against any real Jenkins core dialog
    // markup (data-id="ok" has matched on every version checked so far). Kept in case a future
    // core version drops data-id entirely - picks whichever dialog button is not labelled exactly
    // like the Cancel button we asked for (matched by its own rendered text, so this stays correct
    // under localization instead of guessing an English word).
    var buttons = Array.prototype.slice.call(dlg.querySelectorAll('button'));
    for (var i = 0; i < buttons.length; i++) {
      if (buttons[i].textContent.replace(/^\s+|\s+$/g, '') !== cancelLabel) { return buttons[i]; }
    }
    return null;
  }

  function wireDialogNameInput(dlg, expectedName, cancelLabel) {
    var input = dlg.querySelector('input[type="text"], input:not([type])');
    if (!input) { return; }
    function sync() {
      var btn = findDialogOkButton(dlg, cancelLabel);
      if (!btn) { return; }
      var typed = input.value.replace(/^\s+|\s+$/g, '');
      // Only ever forces the button back to disabled on a mismatch — never re-enables it here, so
      // core's own allowEmpty:false gate (which DOES re-enable it once the field stops being
      // empty, on every keystroke) always still runs first, exactly as before this fix.
      if (typed !== expectedName) { btn.disabled = true; }
    }
    input.addEventListener('input', sync);
    sync();
  }

  function showBlocked(bannerId, title, sections) {
    var banner = document.getElementById(bannerId);
    var plain = [];
    if (banner) {
      banner.innerHTML = '';
      var heading = document.createElement('strong');
      heading.textContent = title;
      banner.appendChild(heading);
    }
    for (var i = 0; i < sections.length; i++) {
      var section = sections[i];
      if (!section.items || section.items.length === 0) { continue; }
      plain.push(section.intro);
      if (banner) {
        var intro = document.createElement('div');
        intro.className = 'ctsync-blocked-note';
        intro.textContent = section.intro;
        banner.appendChild(intro);
        var list = document.createElement('ul');
        banner.appendChild(list);
      }
      for (var j = 0; j < section.items.length; j++) {
        plain.push('  ' + section.items[j].text);
        if (banner) {
          var item = document.createElement('li');
          if (section.items[j].url) {
            var link = document.createElement('a');
            link.href = section.items[j].url;
            link.textContent = section.items[j].text;
            item.appendChild(link);
          } else {
            item.textContent = section.items[j].text;
          }
          list.appendChild(item);
        }
      }
    }
    if (banner) { ctsyncSetHidden(banner, false); }
    if (typeof window !== 'undefined' && window.dialog
        && typeof window.dialog.alert === 'function') {
      window.dialog.alert(title, { message: plain.join('\n'), type: 'destructive' });
    } else {
      notify(title, true);
    }
  }

  function hideBlocked(bannerId) {
    var banner = document.getElementById(bannerId);
    if (banner) {
      banner.innerHTML = '';
      ctsyncSetHidden(banner, true);
    }
  }

  function notify(message, isError) {
    if (typeof window === 'undefined' || !window.notificationBar) { return; }
    window.notificationBar.show(message,
        isError ? window.notificationBar.ERROR : window.notificationBar.SUCCESS);
  }

  function formatTimestamp(epochMillis) {
    var value = Number(epochMillis);
    if (!value) { return ''; }
    return new Date(value).toLocaleString();
  }

  if (typeof window !== 'undefined') {
    window.ctsyncConfirmByName = confirmByName;
    window.ctsyncShowBlocked = showBlocked;
    window.ctsyncHideBlocked = hideBlocked;
    window.ctsyncNotify = notify;
    window.ctsyncFormatTimestamp = formatTimestamp;
    window.ctsyncFormatMessage = format;
  }
})();


/* ---- Icons for script-built markup (iconsBlock tag) ---- */
(function () {
  // Clone of a symbol Jenkins rendered into #ctsyncIcons (see iconsBlock.jelly), or null when the
  // page does not carry the template. The symbols are real SVG, so they inherit currentColor and
  // follow the active theme.
  function icon(name) {
    var tpl = document.getElementById('ctsyncIcons');
    if (!tpl || !tpl.content) { return null; }
    var holder = tpl.content.querySelector('[data-icon="' + name + '"]');
    return holder && holder.firstElementChild ? holder.firstElementChild.cloneNode(true) : null;
  }

  // Replace the element's content with [icon] + text, keeping the text label (glyph plus words).
  function setIconText(el, name, text) {
    while (el.firstChild) { el.removeChild(el.firstChild); }
    var svg = icon(name);
    if (svg) { el.appendChild(svg); }
    if (text) { el.appendChild(document.createTextNode(svg ? ' ' + text : text)); }
  }

  if (typeof window !== 'undefined') {
    window.ctsyncIcon = icon;
    window.ctsyncSetIconText = setIconText;
  }
})();

/* ---- Monaco theme taken from the Jenkins theme ----
   Monaco's stock vs/vs-dark themes carry their own neutral greys, which sit beside the Jenkins
   page as a foreign block. This defines a derived theme whose editor background and foreground are
   the page's own --input-color / --text-color, resolved through a probe element, so it follows
   whichever theme Jenkins is showing. Returns the theme name to pass to monaco.editor.setTheme. */
function ctsyncMonacoTheme(dark) {
  var base = dark ? 'vs-dark' : 'vs';
  try {
    if (!window.monaco || !monaco.editor || !monaco.editor.defineTheme) { return base; }
    var probe = document.createElement('div');
    probe.className = 'ctsync-theme-probe';
    document.body.appendChild(probe);
    var cs = window.getComputedStyle(probe);
    var bg = ctsyncCssColorToHex(cs.backgroundColor);
    var fg = ctsyncCssColorToHex(cs.color);
    var line = ctsyncCssColorToHex(cs.borderTopColor);
    document.body.removeChild(probe);
    if (!bg) { return base; }
    var colors = { 'editor.background': bg, 'editorGutter.background': bg };
    if (fg) { colors['editor.foreground'] = fg; }
    if (line) { colors['editorWidget.border'] = line; }
    var name = dark ? 'ctsync-dark' : 'ctsync-light';
    monaco.editor.defineTheme(name, { base: base, inherit: true, rules: [], colors: colors });
    return name;
  } catch (e) {
    return base;
  }
}

// Any CSS colour (rgb, color(), oklch, color-mix result) to #rrggbb through a 1x1 canvas, which
// converts for us; null when the colour is transparent or cannot be parsed.
function ctsyncCssColorToHex(css) {
  try {
    var canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    var ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    var d = ctx.getImageData(0, 0, 1, 1).data;
    if (d[3] === 0) { return null; }
    var h = function (n) { return ('0' + n.toString(16)).slice(-2); };
    return '#' + h(d[0]) + h(d[1]) + h(d[2]);
  } catch (e) {
    return null;
  }
}
