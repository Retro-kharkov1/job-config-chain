/*
 * CommonConfigSetPage/index.jelly's page-level script.
 *
 * CSP migration (Jenkins hosting requirement, see
 * https://www.jenkins.io/doc/developer/security/csp/): moved out of this page's inline <script>
 * body into this external file, loaded via <script src="${h.getViewResource(it, 'index.js')}"/>.
 * Server-side values that used to be interpolated via Jelly ${...}/<j:out> directly into the
 * script text now arrive as data-* attributes on #ctsyncCommonSeed (a static .js file is never
 * Jelly-evaluated) — see "Seed data" below. Every former inline onclick="..."/onkeydown="..."
 * attribute in this page's own markup is now wired at the bottom of this file, by id for static
 * elements and by event delegation (keyed off data-version-number/data-secret-path/CSS class) for
 * rows rendered or rebuilt at runtime.
 */

/* ---- SharedBlocks/tableToolsBlock.jelly (moved out of its own inline <script>) ----
   Duplicated identically in ConfigTemplatesRootAction/index.js and ConfigTemplatesJobAction/index.js —
   see tableToolsBlock.jelly's own comment for why. Keep all three copies byte-identical. */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncTableToolsStrings');
    return {
      placeholder: (tpl && tpl.dataset.filterPlaceholder) || 'Filter',
      count: (tpl && tpl.dataset.countLabel) || '{0} of {1}',
      noMatches: (tpl && tpl.dataset.noMatches) || 'No rows match the filter',
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

/* ---- SharedBlocks/confirmByNameBlock.jelly (moved out of its own inline <script>) ----
   Duplicated identically in ConfigTemplatesRootAction/index.js and ConfigTemplatesJobAction/index.js. */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncConfirmStrings');
    return {
      cancel: (tpl && tpl.dataset.cancelLabel) || 'Cancel',
      mismatch: (tpl && tpl.dataset.nameMismatch) || 'The typed name does not match.'
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
    window.dialog.prompt(opts.title, {
      message: opts.message,
      allowEmpty: false,
      type: 'destructive',
      okText: opts.okLabel,
      cancelText: s.cancel
    }).then(proceed).catch(function () {
      // Cancelling rejects with no argument — swallow to avoid an unhandled-rejection console error.
    });
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
    if (banner) { banner.style.display = ''; }
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
      banner.style.display = 'none';
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

/* ---- This page's own script (CommonConfigSetPage/index.jelly) ---- */

// Seed data (CSP migration): everything that used to be interpolated via Jelly ${...}/<j:out>
// straight into this script's text now lives on #ctsyncCommonSeed's data-* attributes (see
// index.jelly). getEditorSeedJsonForScript() already returns a COMPLETE JS-string-literal-safe
// encoding (Gson#toJson of the underlying text) — previously embedded directly as JS source via
// <j:out> (so the JS *parser* did one implicit "decode" of the literal). Read back from an HTML
// attribute instead, that implicit decode no longer happens, so it needs exactly one extra
// JSON.parse() layer here to reach the same runtime value this script always worked with.
function commonSeed() {
  var el = document.getElementById('ctsyncCommonSeed');
  return el ? el.dataset : {};
}

var __commonSeedData = commonSeed();
var __seed = JSON.parse(__commonSeedData.editorSeed);
// This Config Set's content type (see multi-format-content.md) — committed and immutable once a
// first version exists, otherwise the picker's live selection (default JSON). Read from the
// already-rendered hidden field rather than a second data-* copy of the same value.
var currentContentType = document.getElementById('contentTypeField').value;
var editor;
var diffEditor;
var yamlCheckTimer = null;
// true = "Editing" mode (normal editable Monaco editor), false = "Compare" mode (read-only
// Monaco diff editor). Both modes render into the SAME #monacoEditor container — this is a
// mode toggle, never a second editor instance (wireframe structural principle).
var editMode = true;
var lastEditContent = __seed;
// "Generate Template" is a third mode of the SAME editor box — not a separate component.
// Tracked independently of editMode/diffEditor above.
var generateMode = false;
var lastGeneratedTemplateText = '';
// Compare interaction redesign (2026-09-01): which single history version (if any) is
// currently being diffed against the draft — click-to-select, no checkbox.
// Re-clicking this same row exits back to edit mode (a simple selectHistoryRow toggle).
var selectedCompareVersion = null;
var lastComparedVersionContent = '';

// ---- Deletion lifecycle --------------------------------------------------------------
// The click asks the server what would break BEFORE prompting for the name. Making somebody
// type an exact Config Set name and only then telling them it cannot be done is worse than
// telling them up front - and the answer is needed anyway, because it is what populates the
// refusal. The server re-checks everything regardless: this response can be stale by the
// time the delete call lands.

function lifecycleStrings() {
  var tpl = document.getElementById('ctsyncLifecycleStrings');
  return tpl ? tpl.dataset : {};
}

function jobItems(entries) {
  var items = [];
  for (var i = 0; i < entries.length; i++) {
    var versions = entries[i].versions.join(', ');
    items.push({
      text: window.ctsyncFormatMessage(lifecycleStrings().jobRow, entries[i].fullName, versions),
      url: entries[i].url
    });
  }
  return items;
}

function showLifecycleRefusal(titleTemplate, introKey, response) {
  var d = lifecycleStrings();
  window.ctsyncShowBlocked('deleteBlockedBanner',
      window.ctsyncFormatMessage(titleTemplate, __commonSeedData.projectKey),
      [{ intro: d[introKey], items: jobItems(response.activeJobs) },
       { intro: d.historicalNote, items: jobItems(response.historicalJobs) }]);
}

function deleteConfigSetClicked() {
  var button = document.getElementById('deleteConfigSetBtn');
  button.disabled = true;
  window.ctsyncHideBlocked('deleteBlockedBanner');
  proxy.deletePreflight(function (t) {
    button.disabled = false;
    var r = t.responseObject();
    var d = lifecycleStrings();
    if (r.blocked) {
      // No prompt at all in this branch: there is nothing for the operator to confirm.
      showLifecycleRefusal(d.deleteDialogTitle, 'blockedIntro', r);
      return;
    }
    if (!r.ok) { window.ctsyncNotify(r.error, true); return; }
    var historical = r.historicalJobs.length > 0
        ? '\n\n' + d.historicalNote + '\n' + jobItems(r.historicalJobs)
            .map(function (item) { return item.text; }).join('\n')
        : '';
    window.ctsyncConfirmByName({
      expectedName: __commonSeedData.projectKey,
      title: window.ctsyncFormatMessage(d.deleteDialogTitle, __commonSeedData.projectKey),
      message: window.ctsyncFormatMessage(d.deleteDialogMessage, __commonSeedData.projectKey) + historical,
      okLabel: d.deleteOkLabel,
      onConfirm: function (typed) {
        proxy.deleteConfigSet(JSON.stringify({ confirmName: typed }), function (t2) {
          var r2 = t2.responseObject();
          if (!r2.ok) {
            if (r2.blocked) { showLifecycleRefusal(d.deleteDialogTitle, 'blockedIntro', r2); }
            else { window.ctsyncNotify(r2.error, true); }
            return;
          }
          // The page identity is gone, so it cannot stay. The list page renders the
          // confirmation server-side from this query parameter - a toast would not survive
          // the navigation.
          window.location.href = __commonSeedData.rootUrl + '/manage/configTemplates/?deleted='
              + encodeURIComponent(r2.projectKey);
        });
      }
    });
  });
}

function restoreConfigSetClicked() {
  var button = document.getElementById('restoreConfigSetBtn');
  button.disabled = true;
  proxy.restoreConfigSet(function (t) {
    button.disabled = false;
    var r = t.responseObject();
    if (!r.ok) { window.ctsyncNotify(r.error, true); return; }
    // Restore changes what the whole page may do, so it is re-rendered rather than patched.
    window.location.reload();
  });
}

function purgeConfigSetClicked() {
  var button = document.getElementById('purgeConfigSetBtn');
  button.disabled = true;
  window.ctsyncHideBlocked('deleteBlockedBanner');
  proxy.purgePreflight(function (t) {
    button.disabled = false;
    var r = t.responseObject();
    var d = lifecycleStrings();
    if (r.blocked) {
      showLifecycleRefusal(d.purgeDialogTitle, 'purgeBlockedIntro', r);
      return;
    }
    if (!r.ok) { window.ctsyncNotify(r.error, true); return; }
    window.ctsyncConfirmByName({
      expectedName: __commonSeedData.projectKey,
      title: window.ctsyncFormatMessage(d.purgeDialogTitle, __commonSeedData.projectKey),
      message: window.ctsyncFormatMessage(d.purgeDialogMessage, __commonSeedData.projectKey,
          r.versionCount),
      okLabel: d.purgeOkLabel,
      onConfirm: function (typed) {
        proxy.purgeConfigSet(JSON.stringify({ confirmName: typed }), function (t2) {
          var r2 = t2.responseObject();
          if (!r2.ok) {
            if (r2.blocked) { showLifecycleRefusal(d.purgeDialogTitle, 'purgeBlockedIntro', r2); }
            else { window.ctsyncNotify(r2.error, true); }
            return;
          }
          window.location.href = __commonSeedData.rootUrl + '/manage/configTemplates/?purged='
              + encodeURIComponent(r2.projectKey);
        });
      }
    });
  });
}

function formatTimestamp(epochMillis) {
  if (epochMillis === null || epochMillis === undefined || isNaN(epochMillis)) { return String(epochMillis); }
  var d = new Date(Number(epochMillis));
  if (isNaN(d.getTime())) { return String(epochMillis); }
  return d.toLocaleString();
}

// Reformat the server-rendered (pre-JS) "Created" cells on initial page load — this
// script tag sits after the table in the document, so the table is already parsed by
// the time this top-level statement runs.
(function () {
  var cells = document.querySelectorAll('.ctsync-created-cell');
  for (var i = 0; i < cells.length; i++) {
    cells[i].textContent = formatTimestamp(cells[i].getAttribute('data-epoch'));
  }
})();

// Initial page load renders versionHistoryTbody/secretsManifestTbody server-side (Jelly
// forEach), never through renderVersionHistoryRows/renderSecretsManifestRows — so those two
// wraps need their own phantom-scroll reconciliation pass here too.
reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
reconcilePhantomScrollSoon(document.getElementById('secretsManifestTableWrap'));
refreshTableFilters(); // re-apply any active column filter to the rebuilt rows

// JSON/XML/YAML all dispatch through this one language-mode lookup — no
// per-format special-casing anywhere else in this script beyond it.
function languageForType(type) {
  if (type === 'XML') { return 'xml'; }
  if (type === 'YAML') { return 'yaml'; }
  return 'json';
}

// Monaco must follow Jenkins' own light/dark appearance (owner report, 2026-09-18) — relative
// luminance of the page's own rendered background colour, WCAG sRGB coefficients; see
// ConfigTemplatesJobAction/index.js's identical function for the full rationale.
function jenkinsPrefersDark() {
  var probe = document.body || document.documentElement;
  var bg = probe ? window.getComputedStyle(probe).backgroundColor : '';
  var m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?/.exec(bg || '');
  if (m && (m[4] === undefined || parseFloat(m[4]) > 0)) {
    var lum = (0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3]) / 255;
    return lum < 0.5;
  }
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

function applyMonacoTheme() {
  if (window.monaco && monaco.editor) {
    monaco.editor.setTheme(jenkinsPrefersDark() ? 'vs-dark' : 'vs');
  }
}

function watchJenkinsTheme() {
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) { mq.addEventListener('change', applyMonacoTheme); }
  }
  if (window.MutationObserver) {
    var obs = new MutationObserver(applyMonacoTheme);
    var opts = { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] };
    obs.observe(document.documentElement, opts);
    if (document.body) { obs.observe(document.body, opts); }
  }
}

require.config({ paths: { vs: __commonSeedData.monacoBase } });
require(['vs/editor/editor.main'], function () {
  monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
    validate: true, allowComments: false, schemas: []
  });
  applyMonacoTheme();
  watchJenkinsTheme();
  registerXmlYamlFormatProviders();
  editor = monaco.editor.create(document.getElementById('monacoEditor'), {
    value: __seed,
    language: languageForType(currentContentType),
    automaticLayout: true
  });
  wireLiveDiagnostics(editor);
  formatEditorContent(editor);
});

// Switching the (still-unlocked) content-type radio must re-create the Monaco
// editor instance with the newly chosen language so diagnostics/format match what will
// actually be validated on Save — not just change the submitted field.
function onContentTypeChange(type) {
  if (currentContentType === type) { return; }
  currentContentType = type;
  document.getElementById('contentTypeField').value = type;
  if (editMode && editor) {
    var val = editor.getValue();
    editor.dispose();
    editor = monaco.editor.create(document.getElementById('monacoEditor'), {
      value: val,
      language: languageForType(currentContentType),
      automaticLayout: true
    });
    wireLiveDiagnostics(editor);
  }
}

// XML gets synchronous, client-side DOMParser diagnostics; YAML gets a debounced
// server round trip (doValidateContent) with the small non-blocking "Checking YAML…"
// indicator; JSON keeps Monaco's own built-in JSON diagnostics (already wired above), so
// this function is a no-op for JSON.
function wireLiveDiagnostics(ed) {
  if (!ed) { return; }
  ed.onDidChangeModelContent(function () {
    if (currentContentType === 'XML') {
      runXmlDiagnostics(ed);
    } else if (currentContentType === 'YAML') {
      scheduleYamlDiagnostics(ed);
    }
  });
  if (currentContentType === 'XML') { runXmlDiagnostics(ed); }
  if (currentContentType === 'YAML') { scheduleYamlDiagnostics(ed); }
}

function runXmlDiagnostics(ed) {
  var markers = [];
  try {
    var doc = new DOMParser().parseFromString(ed.getValue(), 'application/xml');
    var errorNode = doc.getElementsByTagName('parsererror')[0];
    if (errorNode) {
      markers.push({
        severity: monaco.MarkerSeverity.Error,
        message: errorNode.textContent || 'Malformed XML',
        startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1
      });
    }
  } catch (e) {
    markers.push({
      severity: monaco.MarkerSeverity.Error,
      message: String(e),
      startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1
    });
  }
  monaco.editor.setModelMarkers(ed.getModel(), 'ctsync-xml', markers);
}

function scheduleYamlDiagnostics(ed) {
  if (yamlCheckTimer) { clearTimeout(yamlCheckTimer); }
  var indicator = document.getElementById('checkingIndicator');
  if (indicator) { indicator.style.display = 'inline'; }
  var content = ed.getValue();
  yamlCheckTimer = setTimeout(function () {
    proxy.validateContent(JSON.stringify({ content: content, contentType: 'YAML' }), function (t) {
      if (indicator) { indicator.style.display = 'none'; }
      var r = t.responseObject();
      var markers = [];
      if (!r.ok) {
        markers.push({
          severity: monaco.MarkerSeverity.Error,
          message: r.error || 'Malformed YAML',
          startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1
        });
      }
      monaco.editor.setModelMarkers(ed.getModel(), 'ctsync-yaml', markers);
    });
  }, 500);
}

// XML/YAML "Format document" round-trips the same doFormatContent server endpoint
// used by the YAML "checking…" diagnostics' sibling — JSON keeps Monaco's own built-in
// formatter (unchanged), so no provider is registered for it here.
function registerXmlYamlFormatProviders() {
  ['xml', 'yaml'].forEach(function (lang) {
    monaco.languages.registerDocumentFormattingEditProvider(lang, {
      provideDocumentFormattingEdits: function (model) {
        return new Promise(function (resolve) {
          var type = lang === 'xml' ? 'XML' : 'YAML';
          proxy.formatContent(JSON.stringify({ content: model.getValue(), contentType: type }), function (t) {
            var r = t.responseObject();
            if (!r.ok) { resolve([]); return; }
            resolve([{ range: model.getFullModelRange(), text: r.formatted }]);
          });
        });
      }
    });
  });
}

// Auto pretty-print JSON content whenever it's (re)loaded into the editable Monaco
// editor, so the user never sees a minified/however-it-was-stored blob and never has to
// trigger formatting manually. Fire-and-forget: nothing downstream depends on the
// formatted state finishing before the user can type, and formatting is idempotent.
function formatEditorContent(ed) {
  if (!ed) { return; }
  var attemptsLeft = 6;
  function attempt() {
    var before = ed.getValue();
    var action = ed.getAction('editor.action.formatDocument');
    if (!action) {
      if (--attemptsLeft > 0) { setTimeout(attempt, 200); }
      return;
    }
    Promise.resolve(action.run()).then(function () {
      if (ed.getValue() === before && --attemptsLeft > 0) {
        setTimeout(attempt, 200);
      }
    });
  }
  setTimeout(attempt, 0);
}

// Native Jenkins toast (owner requirement, 2026-09-14): replaces the old inline #saveBanner
// div with the same window.notificationBar singleton core uses for the "Apply" toast on
// /job/<name>/configure and /manage/configure.
function showSaveNotification(message, isError) {
  window.notificationBar.show(message,
      isError ? window.notificationBar.ERROR : window.notificationBar.SUCCESS);
}

// Follow-up (2026-09-02): a Common page's very first successful save commits
// (locks) the content type server-side — without a page reload, the still-editable radio
// group must be swapped for the same locked/read-only display the server would have rendered
// on a fresh page load, exactly mirroring the Jelly markup in #contentTypeRow above.
function applyContentTypeLocked(type) {
  if (document.getElementById('contentTypeLockedDisplay')) { return; }
  var unlockedGroup = document.getElementById('contentTypeUnlockedGroup');
  if (!unlockedGroup) { return; }
  var locked = document.createElement('span');
  locked.id = 'contentTypeLockedDisplay';
  locked.textContent = type + ' 🔒 (locked — set at first Save, immutable)';
  unlockedGroup.parentNode.replaceChild(locked, unlockedGroup);
  currentContentType = type;
  document.getElementById('contentTypeField').value = type;
}

// In-place update (2026-09-02): Save/Save & Activate now call proxy.save(...) directly —
// no <form> submit, no page reload. Content comes straight from the live Monaco editor
// (mirrors prepareSubmit's old editMode/lastEditContent fallback exactly).
function saveClicked(activate) {
  var content = (editMode && editor) ? editor.getValue() : lastEditContent;
  var note = document.getElementById('noteField').value;
  var payload = {
    content: content,
    note: note,
    activate: activate,
    contentType: document.getElementById('contentTypeField').value
  };
  document.getElementById('saveBtn').disabled = true;
  document.getElementById('saveActivateBtn').disabled = true;
  proxy.save(JSON.stringify(payload), function (t) {
    document.getElementById('saveBtn').disabled = false;
    document.getElementById('saveActivateBtn').disabled = false;
    var r = t.responseObject();
    if (!r.ok) {
      showSaveNotification(r.error, true);
      return;
    }
    showSaveNotification(activate ? 'Saved and activated' : 'Version saved', false);
    renderVersionHistoryRows(r.versions);
    if (selectedCompareVersion !== null) { highlightSelectedHistoryRow(selectedCompareVersion); }
    if (r.contentTypeLocked) { applyContentTypeLocked(r.contentTypeValue); }
    if (r.exists) { applyConfigSetNowExists(); }
    document.getElementById('noteField').value = '';
  });
}

// Bug fix (2026-09-28): a Common page's very first successful save also flips the record from
// "does not exist yet" to existing — without a page reload, the stale "does not exist yet" banner
// and the still-absent Delete button must be brought in line with that. deleteConfigSetBtn is
// never pre-rendered while !it.exists (see index.jelly) — a live Config Set with nothing to
// delete yet must not offer a Delete button in the DOM at all, only hidden by style, per this
// project's own pre-existing, deliberate contract
// (theDeleteButtonRendersOnALiveConfigSetAndNotOnAnAbsentOne) — so this clones
// #deleteConfigSetBtnTemplate to BUILD it, the same in-place-update templating technique
// buildHistoryRowElement/buildSecretRowElement below already use, rather than
// location.reload()-ing the whole page.
function applyConfigSetNowExists() {
  var banner = document.getElementById('notExistYetBanner');
  if (banner) { banner.style.display = 'none'; }
  if (document.getElementById('deleteConfigSetBtn')) { return; }
  var row = document.getElementById('pageHeaderBtnRow');
  var tpl = document.getElementById('deleteConfigSetBtnTemplate');
  if (!row || !tpl) { return; }
  var deleteBtn = tpl.content.firstElementChild.cloneNode(true);
  // CSP migration convention (matches every other in-place-update template on this page): no
  // inline onclick — wired here via addEventListener instead.
  deleteBtn.addEventListener('click', deleteConfigSetClicked);
  row.appendChild(deleteBtn);
}

// In-place-update row templating (2026-09-02): clones #historyRowTemplate (its static
// label/title text already correctly localized by Jelly) and fills in only the per-row
// dynamic values — used by both saveClicked (may append a version) and activateVersion
// (flips which version is active) instead of location.reload()-ing the whole page.
function renderVersionHistoryRows(versions) {
  var tbody = document.getElementById('versionHistoryTbody');
  tbody.innerHTML = '';
  if (versions.length === 0) {
    tbody.appendChild(buildEmptyStateRow(6, __commonSeedData.emptyVersions));
    reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
    refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
    return;
  }
  for (var i = 0; i < versions.length; i++) {
    tbody.appendChild(buildHistoryRowElement(versions[i]));
  }
  reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
  refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
}

function buildHistoryRowElement(v) {
  var tpl = document.getElementById('historyRowTemplate');
  var tr = tpl.content.firstElementChild.cloneNode(true);
  tr.id = 'historyRow-' + v.versionNumber;
  // CSP migration: no direct tr.onclick/onkeydown/btn.onclick assignment here any more —
  // data-version-number lets the SAME delegated listeners wired for the server-rendered rows
  // (see the "Version history row delegation" section at the bottom of this file) also cover
  // rows rebuilt here, without double-firing a handler on this row.
  tr.setAttribute('data-version-number', v.versionNumber);
  tr.querySelector('.js-version').textContent = 'v' + v.versionNumber;
  tr.querySelector('.js-created').textContent = formatTimestamp(v.timestampEpochMillis);
  tr.querySelector('.js-createdBy').textContent = v.author;
  tr.querySelector('.js-note').textContent = v.note;
  var badge = tr.querySelector('.js-activeBadge');
  badge.textContent = v.active ? ('● ' + tpl.dataset.activeLabel) : '';
  var btn = tr.querySelector('.js-activate-btn');
  if (v.active) {
    btn.disabled = true;
    btn.setAttribute('data-always-disabled', 'true');
    btn.title = tpl.dataset.alreadyActiveTitle;
  } else {
    btn.title = tpl.dataset.activateTitle.replace('{0}', String(v.versionNumber));
    btn.setAttribute('data-version-number', v.versionNumber);
  }
  return tr;
}

// In-place-update row templating (2026-09-02): same technique as the version-history table
// above, for the secrets-manifest table — used by addSecret/removeSecret instead of
// location.reload()-ing the whole page.
function renderSecretsManifestRows(manifest) {
  var tbody = document.getElementById('secretsManifestTbody');
  tbody.innerHTML = '';
  if (manifest.length === 0) {
    tbody.appendChild(buildEmptyStateRow(3, __commonSeedData.emptySecrets));
    reconcilePhantomScrollSoon(document.getElementById('secretsManifestTableWrap'));
    refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
    return;
  }
  for (var i = 0; i < manifest.length; i++) {
    tbody.appendChild(buildSecretRowElement(manifest[i]));
  }
  reconcilePhantomScrollSoon(document.getElementById('secretsManifestTableWrap'));
  refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
}

function buildSecretRowElement(entry) {
  var tpl = document.getElementById('secretRowTemplate');
  var tr = tpl.content.firstElementChild.cloneNode(true);
  tr.querySelector('.js-secret-path').textContent = entry.path;
  tr.querySelector('.js-secret-cred').textContent = entry.credentialId;
  var btn = tr.querySelector('.ctsync-remove-secret-btn');
  // CSP migration: no direct btn.onclick assignment here — data-secret-path lets the delegated
  // listener wired for the server-rendered rows also cover rows rebuilt here.
  btn.setAttribute('data-secret-path', entry.path);
  btn.title = tpl.dataset.removeTitle;
  return tr;
}

function switchToEditMode() {
  if (editMode) { return; }
  if (diffEditor) { diffEditor.dispose(); diffEditor = null; }
  if (editor) { editor.dispose(); editor = null; }
  editor = monaco.editor.create(document.getElementById('monacoEditor'), {
    value: lastEditContent,
    language: languageForType(currentContentType),
    automaticLayout: true
  });
  wireLiveDiagnostics(editor);
  formatEditorContent(editor);
  editMode = true;
  generateMode = false;
  clearHistorySelection();
  document.getElementById('modeEditBtn').className = 'jenkins-button jenkins-button--primary';
  document.getElementById('modeGenerateBtn').className = 'jenkins-button';
  document.getElementById('compareBanner').style.display = 'none';
  document.getElementById('generateBanner').style.display = 'none';
  document.getElementById('generateCopyRow').style.display = 'none';
}

// Click-driven Compare (2026-09-01 redesign, owner report): no checkbox anywhere —
// clicking an unselected version row diffs the CURRENT DRAFT against that one version;
// re-clicking the already-selected row deselects it (back to edit mode, same as
// `switchToEditMode`); clicking a different row while already comparing swaps the
// right-hand pane to the newly clicked version, without ever changing the left-hand pane.
function selectHistoryRow(version) {
  if (selectedCompareVersion === version) {
    switchToEditMode();
    return;
  }
  proxy.compareVersions(JSON.stringify({ version: version }), function (t) {
    var r = t.responseObject();
    if (!r.ok) {
      alert(r.error);
      return;
    }
    enterCompareMode(version, r.content);
  });
}

function enterCompareMode(version, versionContent) {
  if (editor) {
    lastEditContent = editor.getValue();
    editor.dispose();
    editor = null;
  }
  if (diffEditor) {
    diffEditor.dispose();
    diffEditor = null;
  }
  var draftModel = monaco.editor.createModel(lastEditContent, languageForType(currentContentType));
  var versionModel = monaco.editor.createModel(versionContent, languageForType(currentContentType));
  diffEditor = monaco.editor.createDiffEditor(document.getElementById('monacoEditor'), {
    readOnly: true,
    automaticLayout: true,
    renderSideBySide: true
  });
  diffEditor.setModel({ original: draftModel, modified: versionModel });
  editMode = false;
  generateMode = false;
  selectedCompareVersion = version;
  lastComparedVersionContent = versionContent;
  highlightSelectedHistoryRow(version);
  document.getElementById('modeEditBtn').className = 'jenkins-button';
  document.getElementById('modeGenerateBtn').className = 'jenkins-button';
  document.getElementById('compareBannerText').textContent =
      'Comparing the current (unsaved) draft against v' + version + '.';
  document.getElementById('compareBanner').style.display = 'block';
  document.getElementById('generateBanner').style.display = 'none';
  document.getElementById('generateCopyRow').style.display = 'none';
}

// Accepts the version currently being compared into the draft and returns to edit mode.
function loadComparedIntoEditor() {
  if (!selectedCompareVersion) { return; }
  lastEditContent = lastComparedVersionContent;
  switchToEditMode();
}

function highlightSelectedHistoryRow(version) {
  clearHistoryHighlightOnly();
  var row = document.getElementById('historyRow-' + version);
  if (row) { row.className = 'ctsync-history-row ctsync-history-row--selected'; }
}

function clearHistoryHighlightOnly() {
  var rows = document.querySelectorAll('.ctsync-history-row--selected');
  for (var i = 0; i < rows.length; i++) {
    rows[i].className = 'ctsync-history-row';
  }
}

function clearHistorySelection() {
  selectedCompareVersion = null;
  lastComparedVersionContent = '';
  clearHistoryHighlightOnly();
}

// Swaps the SAME editor box into a read-only Monaco instance seeded with the
// walked-and-tokenized ACTIVE version content. Zero-argument proxy.generateTemplate() call
// (server-side signature has no overlayJson/contentJson parameter at all) so there is
// structurally no way this can read an unsaved draft.
function switchToGenerateMode() {
  proxy.generateTemplate(function (t) {
    var r = t.responseObject();
    if (!r.ok) {
      alert(r.error);
      return;
    }
    if (editor) { lastEditContent = editor.getValue(); editor.dispose(); editor = null; }
    if (diffEditor) { diffEditor.dispose(); diffEditor = null; }
    // `template` is already serialized text in the resolved contentType, not a
    // nested JSON object — the client sets it directly into the model rather than
    // JSON.stringify-ing it.
    lastGeneratedTemplateText = r.template;
    editor = monaco.editor.create(document.getElementById('monacoEditor'), {
      value: lastGeneratedTemplateText,
      language: languageForType(r.contentType),
      readOnly: true,
      automaticLayout: true
    });
    formatEditorContent(editor);
    editMode = false;
    generateMode = true;
    clearHistorySelection();
    document.getElementById('modeEditBtn').className = 'jenkins-button';
    document.getElementById('modeGenerateBtn').className = 'jenkins-button jenkins-button--primary';
    document.getElementById('compareBanner').style.display = 'none';
    document.getElementById('generateBanner').style.display = 'block';
    document.getElementById('generateCopyRow').style.display = 'block';
  });
}

// Standard browser Clipboard API, copies the exact editor text verbatim — no added
// marker/comment on secret-bound lines (JSON has no comment syntax; any marker would break
// paste-ready validity).
function copyGeneratedTemplate() {
  var text = (generateMode && editor) ? editor.getValue() : lastGeneratedTemplateText;
  navigator.clipboard.writeText(text);
}

// Only one activate call may be in flight at a time for this Config Set — disable
// EVERY Activate button (not just the one clicked) before the call, and re-enable them all
// on failure (success reloads the page, so there's nothing to re-enable in that path).
function setActivateButtonsDisabled(disabled) {
  var buttons = document.querySelectorAll('.ctsync-activate-btn');
  for (var i = 0; i < buttons.length; i++) {
    // The already-active row's button is permanently disabled — never re-enable it
    // as a side effect of the busy-toggle finishing.
    if (buttons[i].getAttribute('data-always-disabled') === 'true') { continue; }
    buttons[i].disabled = disabled;
  }
}

function activateVersion(v) {
  setActivateButtonsDisabled(true);
  proxy.activate(JSON.stringify({ version: v }), function (t) {
    var r = t.responseObject();
    if (r.ok) {
      // In-place update (2026-09-02): re-render just the version-history table's
      // Active-badge/button-disabled-state from the fresh versions list — nothing else on
      // the page (editor content, accordion state) is touched.
      renderVersionHistoryRows(r.versions);
      if (selectedCompareVersion !== null) { highlightSelectedHistoryRow(selectedCompareVersion); }
    } else {
      setActivateButtonsDisabled(false);
      alert(r.error);
    }
  });
}

function addSecret() {
  var path = document.getElementById('secretPath').value;
  var cred = document.getElementById('secretCredentialId').value;
  proxy.addSecret(JSON.stringify({ path: path, credentialId: cred }), function (t) {
    var r = t.responseObject();
    if (r.ok) {
      // In-place update (2026-09-02): re-render just the secrets-manifest table from the
      // fresh manifest list.
      renderSecretsManifestRows(r.secretsManifest);
      document.getElementById('secretPath').value = '';
    } else {
      alert(r.error);
    }
  });
}

// Unbind a previously-registered secret path. In-place update
// (2026-09-02): re-renders the secrets-manifest table from the fresh manifest list on
// success (same after-action shape as addSecret above) so the removal is reflected without
// a stale row lingering client-side — no page reload.
function removeSecret(path) {
  proxy.removeSecret(JSON.stringify({ path: path }), function (t) {
    var r = t.responseObject();
    if (r.ok) { renderSecretsManifestRows(r.secretsManifest); } else { alert(r.error); }
  });
}

// Phantom-scroll reconciliation (owner report, 2026-09-11): every .ctsync-scroll-table wrap
// measures scrollHeight exactly 2px above clientHeight even for a single/near-empty row —
// a Chromium measurement artifact, not real overflow. Force overflow-y:hidden whenever the
// "overflow" is below a small tolerance so the phantom 2px never paints a scrollbar; genuine
// overflow is many tens/hundreds of px once rows exist, so a 4px tolerance carries no risk of
// masking it. Shared verbatim across every host file that owns a .ctsync-scroll-table wrapper.
function reconcilePhantomScroll(wrapEl) {
  if (!wrapEl) { return; }
  var realOverflow = wrapEl.scrollHeight - wrapEl.clientHeight;
  wrapEl.style.overflowY = (realOverflow > 4) ? 'auto' : 'hidden';
}

function buildEmptyStateRow(colspan, message) {
  var tr = document.createElement('tr');
  tr.className = 'ctsync-empty-state-row';
  var td = document.createElement('td');
  td.colSpan = colspan;
  td.textContent = message;
  tr.appendChild(td);
  return tr;
}

function refreshTableFilters() {
  if (typeof window !== 'undefined'
      && typeof window.ctsyncRefreshTableFilters === 'function') {
    window.ctsyncRefreshTableFilters();
  }
}

function reconcilePhantomScrollSoon(wrapEl) {
  reconcilePhantomScroll(wrapEl);
  if (typeof setTimeout === 'function') {
    setTimeout(function () { reconcilePhantomScroll(wrapEl); }, 50);
  }
}

// ---- CSP migration: wiring that used to be inline onclick="..."/onkeydown="..." HTML
// attributes on CommonConfigSetPage/index.jelly's own markup. ----

(function () {
  function on(id, event, handler) {
    var el = document.getElementById(id);
    if (el) { el.addEventListener(event, handler); }
  }

  on('restoreConfigSetBtn', 'click', restoreConfigSetClicked);
  on('purgeConfigSetBtn', 'click', purgeConfigSetClicked);
  on('deleteConfigSetBtn', 'click', deleteConfigSetClicked);
  on('addSecretBtn', 'click', addSecret);
  on('modeEditBtn', 'click', switchToEditMode);
  on('modeGenerateBtn', 'click', switchToGenerateMode);
  on('loadComparedBtn', 'click', loadComparedIntoEditor);
  on('backToEditingBtn', 'click', switchToEditMode);
  on('copyTemplateBtn', 'click', copyGeneratedTemplate);
  on('saveBtn', 'click', function () { saveClicked(false); });
  on('saveActivateBtn', 'click', function () { saveClicked(true); });
  Array.prototype.forEach.call(document.querySelectorAll('input[name="contentTypeRadio"]'),
      function (radio) {
        radio.addEventListener('click', function () { onContentTypeChange(this.value); });
      });

  // Remove-secret buttons carry data-secret-path (server-rendered AND rebuilt via
  // buildSecretRowElement above) — one delegated listener on the persistent tbody covers both.
  var secretsManifestTbody = document.getElementById('secretsManifestTbody');
  if (secretsManifestTbody) {
    secretsManifestTbody.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('.ctsync-remove-secret-btn');
      if (btn) { removeSecret(btn.getAttribute('data-secret-path')); }
    });
  }

  // Version-history rows carry data-version-number (server-rendered AND rebuilt via
  // buildHistoryRowElement above) — one delegated listener on the persistent tbody covers both,
  // with no direct onclick/onkeydown property on the row itself any more.
  var versionHistoryTbody = document.getElementById('versionHistoryTbody');
  if (versionHistoryTbody) {
    versionHistoryTbody.addEventListener('click', function (e) {
      var activateBtn = e.target.closest && e.target.closest('.ctsync-activate-btn');
      if (activateBtn && activateBtn.getAttribute('data-always-disabled') !== 'true') {
        e.stopPropagation();
        activateVersion(Number(activateBtn.getAttribute('data-version-number')));
        return;
      }
      var row = e.target.closest && e.target.closest('.ctsync-history-row');
      if (row) { selectHistoryRow(Number(row.getAttribute('data-version-number'))); }
    });
    versionHistoryTbody.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') { return; }
      var row = e.target.closest && e.target.closest('.ctsync-history-row');
      if (row) {
        e.preventDefault();
        selectHistoryRow(Number(row.getAttribute('data-version-number')));
      }
    });
  }
})();
