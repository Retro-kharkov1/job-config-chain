/* @include io.jenkins.plugins.jobconfigchain.adjuncts.ctsyncSharedScript */
/*
 * Job-level Config Chains page-level script (on top of ctsyncSharedScript). Loaded via
 * <st:adjunct includes="io.jenkins.plugins.jobconfigchain.adjuncts.ctsyncJobScript"/> at the end of the page,
 * after the Monaco loader. CSP-clean: server-side values arrive as data-* attributes on the
 * page's seed element, and every handler is wired here via addEventListener.
 */

/* ---- This page's own script (ConfigTemplatesJobAction/index.jelly + the four SharedBlocks
   fragments it includes) ---- */

// Seed data (CSP migration): everything that used to be interpolated via Jelly ${...}/<j:out>
// straight into this script's text now lives on #ctsyncJobSeed's data-* attributes (see
// index.jelly). getEditorSeedJsonForScript()/getAvailableCommonProjectKeysJsonForScript()/
// getBaseChainSeedJsonForScript()/getCommonVersionCatalogJsonForScript() each already return a
// COMPLETE JS-string-literal-safe encoding (Gson#toJson of the underlying text) — previously
// embedded directly as JS source via <j:out> (so the JS *parser* did one implicit "decode" of the
// literal). Read back from an HTML attribute instead, that implicit decode no longer happens, so
// each of those four values needs exactly one extra JSON.parse() layer here to reach the same
// runtime value this script always worked with.
function jobSeed() {
  var el = document.getElementById('ctsyncJobSeed');
  return el ? el.dataset : {};
}

var __seed = jobSeed();
var __overrideSeed = JSON.parse(__seed.editorSeed);
var __availableProjectKeys = JSON.parse(__seed.availableProjectKeys);
var __baseChainSeed = JSON.parse(__seed.baseChainSeed);
var __commonVersionCatalog = JSON.parse(__seed.commonVersionCatalog);
var overrideEditor, mergedEditor, mergedBasesEditor, debounceHandle, diffEditor;
var generatedTemplateEditor;
var lastGeneratedTemplateText = '';
var editMode = true;
var lastEditContent = __overrideSeed;
var selectedCompareVersion = null;
var lastComparedVersionContent = '';

// Base-chain editor draft state — deliberately no explicitlyStandalone concept at all (see
// ConfigTemplatesJobAction's own javadoc): an empty baseChainRows array unambiguously means
// "no bases," never a synthesized self-referencing default.
var availableProjectKeys = JSON.parse(__availableProjectKeys);
var commonVersionCatalog = JSON.parse(__commonVersionCatalog);
var baseChainRows = JSON.parse(__baseChainSeed);
var baseChainRowExpanded = baseChainRows.map(function () { return false; });
var lastPerReference = [];
// Bug 1 fix (tech-lead contract, 2026-09-10): one live Monaco diff-editor instance per
// currently-expanded base-chain row, tracked here so renderBaseChainRows() can dispose every
// instance from the PREVIOUS render before buildBaseChainRowElement creates fresh ones on
// this render — otherwise every keystroke-triggered recompute (a fresh renderBaseChainRows()
// call) leaks one more Monaco instance per still-expanded row.
var baseChainDiffEditors = [];
var lastSavedOverrideContent = __overrideSeed;
var lastSavedBaseChainJson = __baseChainSeed;
var currentContentType = __seed.contentTypeValue;
var lastSavedContentType = currentContentType;
var yamlCheckTimer = null;

function formatTimestamp(epochMillis) {
  if (epochMillis === null || epochMillis === undefined || isNaN(epochMillis)) { return String(epochMillis); }
  var d = new Date(Number(epochMillis));
  if (isNaN(d.getTime())) { return String(epochMillis); }
  return d.toLocaleString();
}

(function () {
  var cells = document.querySelectorAll('.ctsync-created-cell');
  for (var i = 0; i < cells.length; i++) {
    cells[i].textContent = formatTimestamp(cells[i].getAttribute('data-epoch'));
  }
})();

// Initial page load renders versionHistoryTbody/secretsManifestTbody server-side (Jelly
// forEach), never through renderVersionHistoryRows/renderSecretsManifestRows — so those two
// wraps need their own phantom-scroll reconciliation pass here too (baseChainRowsTableWrap
// is covered by the renderBaseChainRows() call fired from the require() callback below).
reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
reconcilePhantomScrollSoon(document.getElementById('secretsManifestTableWrap'));
refreshTableFilters(); // re-apply any active column filter to the rebuilt rows

function languageForType(type) {
  if (type === 'XML') { return 'xml'; }
  if (type === 'YAML') { return 'yaml'; }
  return 'json';
}

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

function wireLiveDiagnostics(ed) {
  if (!ed) { return; }
  ed.onDidChangeModelContent(function () {
    if (currentContentType === 'XML') {
      runXmlDiagnostics(ed);
    } else if (currentContentType === 'YAML') {
      scheduleYamlDiagnostics(ed);
    }
  });
}

// Monaco must follow Jenkins' own light/dark appearance (owner report, 2026-09-18) — see this
// function's original inline-script comment history for the full rationale (relative luminance,
// WCAG sRGB coefficients, why no core theme flag exists to key off instead).
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
    monaco.editor.setTheme(ctsyncMonacoTheme(jenkinsPrefersDark()));
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

require.config({ paths: { vs: __seed.monacoBase } });
require(['vs/editor/editor.main'], function () {
  monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
    validate: true, allowComments: false, schemas: []
  });
  applyMonacoTheme();
  watchJenkinsTheme();
  registerXmlYamlFormatProviders();
  overrideEditor = monaco.editor.create(document.getElementById('overrideEditor'), {
    value: __overrideSeed, language: languageForType(currentContentType), automaticLayout: true
  });
  formatEditorContent(overrideEditor);
  wireLiveDiagnostics(overrideEditor);
  mergedEditor = monaco.editor.create(document.getElementById('mergedEditor'), {
    value: '', language: languageForType(currentContentType), readOnly: true, automaticLayout: true
  });
  mergedBasesEditor = monaco.editor.create(document.getElementById('mergedBasesEditor'), {
    value: '', language: languageForType(currentContentType), readOnly: true, automaticLayout: true
  });
  updateContentTypeRowVisibility();
  renderBaseChainRows();
  recomputeMerge();
  attachOverrideChangeListener();
});

// Visible only while the base chain is genuinely empty (no chain to resolve a type from) —
// no explicitlyStandalone checkbox concept here, unlike EnvConfigSetPage: an empty
// baseChainRows array is itself the trigger.
function updateContentTypeRowVisibility() {
  var row = document.getElementById('contentTypeRow');
  if (row) { row.style.display = (baseChainRows.length === 0) ? '' : 'none'; }
}

function onContentTypeChange(type) {
  if (currentContentType === type) { return; }
  currentContentType = type;
  var lang = languageForType(type);
  if (overrideEditor) { monaco.editor.setModelLanguage(overrideEditor.getModel(), lang); }
  if (mergedEditor) { monaco.editor.setModelLanguage(mergedEditor.getModel(), lang); }
  if (mergedBasesEditor) { monaco.editor.setModelLanguage(mergedBasesEditor.getModel(), lang); }
  recomputeMerge();
}

function applyContentTypeLocked(type) {
  if (document.getElementById('contentTypeLockedDisplay')) { return; }
  var unlockedGroup = document.getElementById('contentTypeUnlockedGroup');
  if (!unlockedGroup) { return; }
  var locked = document.createElement('span');
  locked.id = 'contentTypeLockedDisplay';
  locked.className = 'ctsync-icon-text';
  locked.appendChild(document.createTextNode(type));
  var lockIcon = window.ctsyncIcon && window.ctsyncIcon('lock');
  if (lockIcon) { locked.appendChild(lockIcon); }
  locked.appendChild(document.createTextNode('(locked — set at first Save, immutable)'));
  unlockedGroup.parentNode.replaceChild(locked, unlockedGroup);
  currentContentType = type;
}

// ---- Base-chain editor ----

function addBaseChainRow() {
  var defaultProject = availableProjectKeys.length > 0 ? availableProjectKeys[0] : '';
  baseChainRows.push({ projectKey: defaultProject, pinMode: 'ACTIVE', pinnedVersionNumber: 0 });
  baseChainRowExpanded.push(false);
  updateContentTypeRowVisibility();
  renderBaseChainRows();
  recomputeMerge();
}

function removeBaseChainRow(index) {
  baseChainRows.splice(index, 1);
  baseChainRowExpanded.splice(index, 1);
  updateContentTypeRowVisibility();
  renderBaseChainRows();
  recomputeMerge();
}

function onExplicitlyStandaloneChange(checked) {
  // Dead code preserved as-is by the CSP migration: this checkbox never actually renders on the
  // job route (isExplicitlyStandaloneSupported() is always false there), and this function was
  // never defined even before the migration — see baseChainBlock.jelly's own note.
}

// Shared reorder helper (drag-and-drop + ▲/▼ buttons both funnel through this) — splices
// baseChainRows/baseChainRowExpanded from fromIndex to toIndex, then re-renders and
// recomputes the merge exactly once.
function reorderBaseChainRow(fromIndex, toIndex) {
  if (fromIndex === toIndex || fromIndex < 0 || fromIndex >= baseChainRows.length ||
      toIndex < 0 || toIndex >= baseChainRows.length) { return; }
  var row = baseChainRows.splice(fromIndex, 1)[0];
  baseChainRows.splice(toIndex, 0, row);
  var expanded = baseChainRowExpanded.splice(fromIndex, 1)[0];
  baseChainRowExpanded.splice(toIndex, 0, expanded);
  renderBaseChainRows();
  recomputeMerge();
}

function moveBaseChainRow(index, delta) {
  var target = index + delta;
  if (target < 0 || target >= baseChainRows.length) { return; }
  reorderBaseChainRow(index, target);
}

// Drag-and-drop state — tracks the index a drag started from, and the row element
// currently marked as the drop target, so dragend can always clean up regardless of
// whether drop succeeded.
var baseChainDragSourceIndex = null;
var baseChainDropTargetRow = null;

function clearBaseChainDropTarget() {
  if (baseChainDropTargetRow) {
    baseChainDropTargetRow.classList.remove('ctsync-basechain-row--drop-target');
    baseChainDropTargetRow = null;
  }
}

function toggleBaseChainRowExpanded(index) {
  baseChainRowExpanded[index] = !baseChainRowExpanded[index];
  renderBaseChainRows();
}

function projectOptionsForRow(index) {
  if (index === 0 || baseChainRows.length === 0) {
    return availableProjectKeys;
  }
  var contextType = commonVersionCatalog.typeByProject[baseChainRows[0].projectKey];
  if (!contextType) {
    return availableProjectKeys;
  }
  return availableProjectKeys.filter(function (key) {
    return commonVersionCatalog.typeByProject[key] === contextType;
  });
}

function onBaseChainProjectChange(index, projectKey) {
  baseChainRows[index].projectKey = projectKey;
  baseChainRows[index].pinMode = 'ACTIVE';
  baseChainRows[index].pinnedVersionNumber = 0;
  renderBaseChainRows();
  recomputeMerge();
}

function onBaseChainModeChange(index, mode) {
  baseChainRows[index].pinMode = mode;
  if (mode === 'ACTIVE') {
    baseChainRows[index].pinnedVersionNumber = 0;
  } else {
    var versions = commonVersionCatalog.versionsByProject[baseChainRows[index].projectKey] || [];
    baseChainRows[index].pinnedVersionNumber = versions.length > 0 ? versions[0].version : 0;
  }
  renderBaseChainRows();
  recomputeMerge();
}

function onBaseChainPinnedVersionChange(index, versionNumber) {
  baseChainRows[index].pinnedVersionNumber = parseInt(versionNumber, 10);
  recomputeMerge();
}

function renderBaseChainRows() {
  var body = document.getElementById('baseChainRowsBody');
  // Bug 1 fix: dispose every base-chain-row diff-editor instance from the previous render
  // before rebuilding — see baseChainDiffEditors' own declaration comment above.
  for (var d = 0; d < baseChainDiffEditors.length; d++) {
    baseChainDiffEditors[d].dispose();
  }
  baseChainDiffEditors = [];
  body.innerHTML = '';
  for (var i = 0; i < baseChainRows.length; i++) {
    var built = buildBaseChainRowElement(baseChainRows[i], i);
    if (Array.isArray(built)) {
      built.forEach(function (el) { body.appendChild(el); });
    } else {
      body.appendChild(built);
    }
  }
  document.getElementById('baseChainField').value = JSON.stringify(baseChainRows);
  if (baseChainRows.length === 0) {
    body.appendChild(buildEmptyStateRow(7, __seed.emptyBasechain));
  }
  reconcilePhantomScrollSoon(document.getElementById('baseChainRowsTableWrap'));
}

// Shared by every AJAX-rendered table on this page (ux-ui-designer correction,
// 2026-09-11): builds the same "single spanning row" empty-state markup the server itself
// renders on initial page load, so a table that becomes empty via an in-place update
// matches the server-rendered shape exactly instead of hiding the whole table/header.
function buildEmptyStateRow(colspan, message) {
  var tr = document.createElement('tr');
  tr.className = 'ctsync-empty-state-row';
  var td = document.createElement('td');
  td.colSpan = colspan;
  td.textContent = message;
  tr.appendChild(td);
  return tr;
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

// A version's display label. Kept in one place because it is produced for the option
// text, for the truncation comparison, and for the info tooltip - three copies of the same
// concatenation would be three chances for them to drift apart.
function baseChainVersionLabel(version) {
  return 'v' + version.version + ' — ' + version.note;
}

// Notes are free text with no length limit, so the label is shortened for display. Returns
// the input unchanged when it already fits - callers rely on that to detect whether
// anything was actually elided.
function truncateVersionLabel(label) {
  var max = 40;
  return label.length > max ? label.slice(0, max - 1) + '…' : label;
}

// Localized text for the JS-built base-chain rows. The strings live in
// baseChainBlock.properties (and its _uk/_ru siblings) and are carried into the page as
// data-* attributes on the baseChainRowStrings template, because Jelly cannot substitute
// ${%key} inside a script block. Falling back to the key name rather than to hardcoded
// English keeps a missing attribute visible instead of silently looking correct in one
// language only - which is the failure mode this whole pass exists to remove.
function baseChainText(name) {
  var tpl = document.getElementById('baseChainRowStrings');
  var value = tpl ? tpl.dataset[name] : null;
  return value ? value : name;
}

// Minimal MessageFormat-style positional substitution, enough for the {0}/{1}/{2} the
// base-chain strings use. Written out rather than pulled in, since the page carries no
// i18n runtime of its own.
function baseChainFormat(name) {
  var args = Array.prototype.slice.call(arguments, 1);
  return baseChainText(name).replace(/\{(\d+)\}/g, function (whole, i) {
    return args[Number(i)] !== undefined ? String(args[Number(i)]) : whole;
  });
}

function buildBaseChainRowElement(row, index) {
  var tr = document.createElement('tr');
  tr.className = 'ctsync-basechain-row';
  tr.draggable = true;

  // Drag handle cell — only the handle itself starts a row drag; other interactive
  // controls in this row (select/radios/buttons/toggle) must keep working normally.
  var handleTd = document.createElement('td');
  handleTd.className = 'ctsync-basechain-drag-cell';
  var handle = document.createElement('span');
  handle.className = 'ctsync-basechain-drag-handle';
  handle.setAttribute('aria-hidden', 'true');
  handle.title = baseChainText('dragHandleTitle');
  handle.innerHTML = '<svg viewBox="0 0 8 16" width="12" height="24" aria-hidden="true" focusable="false">'
      + '<circle cx="2" cy="2" r="1.3" fill="currentColor"/><circle cx="6" cy="2" r="1.3" fill="currentColor"/>'
      + '<circle cx="2" cy="8" r="1.3" fill="currentColor"/><circle cx="6" cy="8" r="1.3" fill="currentColor"/>'
      + '<circle cx="2" cy="14" r="1.3" fill="currentColor"/><circle cx="6" cy="14" r="1.3" fill="currentColor"/>'
      + '</svg>';
  handleTd.appendChild(handle);
  tr.appendChild(handleTd);

  tr.addEventListener('dragstart', (function (idx) {
    return function (e) {
      var fromHandle = e.target && e.target.closest && e.target.closest('.ctsync-basechain-drag-handle');
      if (!fromHandle) { e.preventDefault(); return; }
      baseChainDragSourceIndex = idx;
      e.dataTransfer.setData('text/plain', String(idx));
      e.dataTransfer.effectAllowed = 'move';
      tr.classList.add('ctsync-basechain-row--dragging');
    };
  })(index));

  tr.addEventListener('dragover', (function (idx) {
    return function (e) {
      if (baseChainDragSourceIndex === null) { return; }
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      if (baseChainDropTargetRow !== tr) {
        clearBaseChainDropTarget();
        tr.classList.add('ctsync-basechain-row--drop-target');
        baseChainDropTargetRow = tr;
      }
    };
  })(index));

  tr.addEventListener('drop', (function (idx) {
    return function (e) {
      e.preventDefault();
      var sourceIndex = baseChainDragSourceIndex;
      clearBaseChainDropTarget();
      if (sourceIndex === null || sourceIndex === idx) { return; }
      reorderBaseChainRow(sourceIndex, idx);
    };
  })(index));

  tr.addEventListener('dragend', function () {
    baseChainDragSourceIndex = null;
    clearBaseChainDropTarget();
    var rows = document.querySelectorAll('.ctsync-basechain-row--dragging');
    for (var r = 0; r < rows.length; r++) { rows[r].classList.remove('ctsync-basechain-row--dragging'); }
  });

  var toggleTd = document.createElement('td');
  var toggleLink = document.createElement('a');
  toggleLink.href = '#';
  toggleLink.className = 'ctsync-basechain-row-toggle';
  toggleLink.setAttribute('role', 'button');
  toggleLink.setAttribute('aria-expanded', baseChainRowExpanded[index] ? 'true' : 'false');
  toggleLink.title = baseChainRowExpanded[index] ? 'Collapse resolved content' : 'Expand resolved content';
  toggleLink.setAttribute('aria-label', toggleLink.title);
  var chevron = ctsyncIcon(baseChainRowExpanded[index] ? 'expanded' : 'collapsed');
  if (chevron) { toggleLink.appendChild(chevron); }
  toggleLink.onclick = (function (idx) {
    return function (e) { e.preventDefault(); toggleBaseChainRowExpanded(idx); };
  })(index);
  toggleTd.appendChild(toggleLink);
  tr.appendChild(toggleTd);

  var idxTd = document.createElement('td');
  idxTd.textContent = (index + 1);
  tr.appendChild(idxTd);

  var projectTd = document.createElement('td');
  var projectSelect = document.createElement('select');
  projectSelect.className = 'jenkins-select__input';
  var options = projectOptionsForRow(index);
  for (var p = 0; p < options.length; p++) {
    var opt = document.createElement('option');
    opt.value = options[p];
    opt.text = options[p];
    if (options[p] === row.projectKey) { opt.selected = true; }
    projectSelect.appendChild(opt);
  }
  projectSelect.onchange = (function (idx) {
    return function () { onBaseChainProjectChange(idx, this.value); };
  })(index);
  // Inline flex row (owner report, 2026-09-11): the select is block-level/full-width
  // inside its td, which pushed the open-link anchor onto its own line below it instead
  // of sitting beside it — wrap both in a flex row so they render side by side.
  var configKeyWrap = document.createElement('div');
  configKeyWrap.className = 'ctsync-basechain-config-key';
  configKeyWrap.appendChild(projectSelect);
  // Open-in-new-tab link next to the Config Key select (docs/design/job-wireframe.md,
  // Block 3) — same convention as EnvConfigSetPage's identical row builder. Small
  // icon-button treatment (owner report, 2026-09-11), matching the row-toggle chevron's
  // visual language rather than a bare Unicode glyph link.
  if (row.projectKey) {
    var openLink = document.createElement('a');
    openLink.href = __seed.rootUrl + '/configChains/' + encodeURIComponent(row.projectKey) + '/';
    openLink.target = '_blank';
    openLink.rel = 'noopener noreferrer';
    openLink.className = 'ctsync-basechain-open-link';
    openLink.title = baseChainFormat('openInNewTabTitle', row.projectKey);
    openLink.setAttribute('aria-label', openLink.title);
    var externalIcon = ctsyncIcon('external');
    if (externalIcon) { openLink.appendChild(externalIcon); }
    configKeyWrap.appendChild(openLink);
  }
  projectTd.appendChild(configKeyWrap);
  tr.appendChild(projectTd);

  var modeTd = document.createElement('td');
  modeTd.className = 'ctsync-basechain-mode-cell';
  modeTd.appendChild(buildModeRadio(row, index, 'ACTIVE', 'Active'));
  modeTd.appendChild(buildModeRadio(row, index, 'PINNED', 'Pin'));
  tr.appendChild(modeTd);

  var versionTd = document.createElement('td');
  if (row.pinMode === 'PINNED') {
    var versionCell = document.createElement('div');
    versionCell.className = 'ctsync-basechain-version-cell';

    var versionSelect = document.createElement('select');
    versionSelect.className = 'jenkins-select__input';
    var versions = commonVersionCatalog.versionsByProject[row.projectKey] || [];
    var fullLabels = {};
    for (var v = 0; v < versions.length; v++) {
      var vopt = document.createElement('option');
      vopt.value = versions[v].version;
      var fullLabel = baseChainVersionLabel(versions[v]);
      fullLabels[String(versions[v].version)] = fullLabel;
      vopt.text = truncateVersionLabel(fullLabel);
      // The untruncated label also rides along as the option's own tooltip, so a user
      // browsing the open dropdown can read a long note without closing it first.
      vopt.title = fullLabel;
      if (versions[v].version === row.pinnedVersionNumber) { vopt.selected = true; }
      versionSelect.appendChild(vopt);
    }
    versionCell.appendChild(versionSelect);

    // Info affordance carrying the SELECTED version's full label. Shown only when
    // something is actually elided - a short note is fully readable in the select, and an
    // icon that reveals nothing new is just noise.
    var infoIcon = document.createElement('span');
    infoIcon.className = 'ctsync-basechain-version-info';
    infoIcon.setAttribute('aria-hidden', 'true');
    var infoSvg = ctsyncIcon('info');
    if (infoSvg) { infoIcon.appendChild(infoSvg); }
    versionCell.appendChild(infoIcon);

    var syncVersionInfo = function () {
      var label = fullLabels[String(versionSelect.value)] || '';
      var elided = label !== truncateVersionLabel(label);
      infoIcon.title = label;
      infoIcon.style.display = elided ? '' : 'none';
    };
    syncVersionInfo();

    versionSelect.onchange = (function (idx) {
      return function () {
        onBaseChainPinnedVersionChange(idx, this.value);
        syncVersionInfo();
      };
    })(index);
    versionTd.appendChild(versionCell);
  }
  tr.appendChild(versionTd);

  var actionsTd = document.createElement('td');
  actionsTd.className = 'ctsync-basechain-actions-cell';
  var upBtn = document.createElement('button');
  upBtn.type = 'button';
  upBtn.className = 'jenkins-button ctsync-basechain-reorder-btn';
  upBtn.title = baseChainText('moveUpTitle');
  upBtn.setAttribute('aria-label', upBtn.title);
  { var ic_upBtn = ctsyncIcon('up'); if (ic_upBtn) { upBtn.appendChild(ic_upBtn); } }
  upBtn.disabled = (index === 0);
  upBtn.onclick = (function (idx) { return function () { moveBaseChainRow(idx, -1); }; })(index);
  actionsTd.appendChild(upBtn);

  var downBtn = document.createElement('button');
  downBtn.type = 'button';
  downBtn.className = 'jenkins-button ctsync-basechain-reorder-btn';
  downBtn.title = baseChainText('moveDownTitle');
  downBtn.setAttribute('aria-label', downBtn.title);
  { var ic_downBtn = ctsyncIcon('down'); if (ic_downBtn) { downBtn.appendChild(ic_downBtn); } }
  downBtn.disabled = (index === baseChainRows.length - 1);
  downBtn.onclick = (function (idx) { return function () { moveBaseChainRow(idx, 1); }; })(index);
  actionsTd.appendChild(downBtn);

  var removeBtn = document.createElement('button');
  removeBtn.type = 'button';
  removeBtn.className = 'jenkins-button jenkins-button--destructive ctsync-basechain-remove-btn';
  removeBtn.title = baseChainText('removeTitle');
  removeBtn.setAttribute('aria-label', removeBtn.title);
  { var ic_removeBtn = ctsyncIcon('remove'); if (ic_removeBtn) { removeBtn.appendChild(ic_removeBtn); } }
  removeBtn.onclick = (function (idx) { return function () { removeBaseChainRow(idx); }; })(index);
  actionsTd.appendChild(removeBtn);

  tr.appendChild(actionsTd);

  if (!baseChainRowExpanded[index]) {
    return tr;
  }

  var detailTr = document.createElement('tr');
  detailTr.className = 'ctsync-basechain-row-detail';
  var detailTd = document.createElement('td');
  detailTd.colSpan = 7;
  if (index < lastPerReference.length) {
    var curRef = lastPerReference[index];
    var caption = document.createElement('div');
    caption.className = 'ctsync-basechain-resolved-caption';
    caption.textContent = baseChainFormat('resolvedCaption', curRef.projectKey,
        curRef.pinMode, curRef.resolvedVersionNumber);
    detailTd.appendChild(caption);
    var diffContainer = document.createElement('div');
    diffContainer.className = 'ctsync-basechain-diff-container ctsync-editor-frame ctsync-editor-frame--short';
    detailTd.appendChild(diffContainer);
    var leftContent = index === 0 ? '{}' : lastPerReference[index - 1].cumulativeJson;
    var rightContent = curRef.cumulativeJson;
    var lang = languageForType(currentContentType);
    var leftModel = monaco.editor.createModel(leftContent, lang);
    var rightModel = monaco.editor.createModel(rightContent, lang);
    var rowDiffEditor = monaco.editor.createDiffEditor(diffContainer, {
      readOnly: true,
      automaticLayout: true,
      renderSideBySide: true
    });
    rowDiffEditor.setModel({ original: leftModel, modified: rightModel });
    baseChainDiffEditors.push(rowDiffEditor);
  } else {
    detailTd.textContent = baseChainText('notResolvable');
  }
  detailTr.appendChild(detailTd);
  return [tr, detailTr];
}

function buildModeRadio(row, index, value, label) {
  var wrapper = document.createElement('label');
  var radio = document.createElement('input');
  radio.type = 'radio';
  radio.name = 'baseChainMode-' + index;
  radio.checked = (row.pinMode === value);
  radio.onchange = (function (idx, v) { return function () { onBaseChainModeChange(idx, v); }; })(index, value);
  wrapper.appendChild(radio);
  wrapper.appendChild(document.createTextNode(' ' + label));
  return wrapper;
}

function toggleBaseChainDetail(versionNumber) {
  var el = document.getElementById('baseChainDetail-' + versionNumber);
  if (el) { el.style.display = (el.style.display === 'none') ? 'block' : 'none'; }
}

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

function attachOverrideChangeListener() {
  overrideEditor.onDidChangeModelContent(function () {
    clearTimeout(debounceHandle);
    debounceHandle = setTimeout(recomputeMerge, 300);
  });
}

function switchToEditMode() {
  if (editMode) { return; }
  if (diffEditor) { diffEditor.dispose(); diffEditor = null; }
  overrideEditor = monaco.editor.create(document.getElementById('overrideEditor'), {
    value: lastEditContent, language: languageForType(currentContentType), automaticLayout: true
  });
  formatEditorContent(overrideEditor);
  wireLiveDiagnostics(overrideEditor);
  attachOverrideChangeListener();
  recomputeMerge();
  editMode = true;
  clearHistorySelection();
  document.getElementById('modeEditBtn').className = 'jenkins-button jenkins-button--primary';
  document.getElementById('compareBanner').style.display = 'none';
}

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
  clearTimeout(debounceHandle);
  if (overrideEditor) {
    lastEditContent = overrideEditor.getValue();
    overrideEditor.dispose();
    overrideEditor = null;
  }
  if (diffEditor) {
    diffEditor.dispose();
    diffEditor = null;
  }
  var draftModel = monaco.editor.createModel(lastEditContent, languageForType(currentContentType));
  var versionModel = monaco.editor.createModel(versionContent, languageForType(currentContentType));
  diffEditor = monaco.editor.createDiffEditor(document.getElementById('overrideEditor'), {
    readOnly: true,
    automaticLayout: true,
    renderSideBySide: true
  });
  diffEditor.setModel({ original: draftModel, modified: versionModel });
  editMode = false;
  selectedCompareVersion = version;
  lastComparedVersionContent = versionContent;
  highlightSelectedHistoryRow(version);
  document.getElementById('modeEditBtn').className = 'jenkins-button';
  document.getElementById('compareBannerText').textContent =
      'Comparing the current (unsaved) draft against v' + version + '.';
  document.getElementById('compareBanner').style.display = 'block';
}

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

// Bug 3 fix (tech-lead contract, 2026-09-10): mergeRequestSeq is a monotonically increasing
// per-recompute token so only the response whose token still matches the CURRENT token when it
// lands is applied — see this function's original inline-script comment history for the full race
// this guards against.
var mergeRequestSeq = 0;

function recomputeMerge() {
  if (!overrideEditor) { return; }
  clearTimeout(debounceHandle); // this recompute is happening now — no pending debounce needed
  document.getElementById('baseChainField').value = JSON.stringify(baseChainRows);
  var requestToken = ++mergeRequestSeq;
  // Simpler signature than EnvConfigSetPage's jsPreviewMerge: no explicitlyStandalone
  // argument at all — an empty baseChainRows array already means "no bases."
  proxy.previewMerge(overrideEditor.getValue(), JSON.stringify(baseChainRows),
      currentContentType, function (t) {
    if (requestToken !== mergeRequestSeq) { return; } // superseded by a newer recompute — ignore
    var r = t.responseObject();
    var indicator = document.getElementById('mergedStaleIndicator');
    if (r.ok) {
      if (r.contentType && r.contentType !== currentContentType) {
        currentContentType = r.contentType;
        monaco.editor.setModelLanguage(overrideEditor.getModel(), languageForType(currentContentType));
        monaco.editor.setModelLanguage(mergedEditor.getModel(), languageForType(currentContentType));
        monaco.editor.setModelLanguage(mergedBasesEditor.getModel(), languageForType(currentContentType));
      }
      mergedEditor.setValue(r.merged);
      mergedBasesEditor.setValue(r.mergedBases);
      indicator.style.display = 'none';
      lastPerReference = r.perReference || [];
      renderBaseChainRows();
    } else {
      indicator.style.display = 'block';
    }
  });
}

// Native Jenkins toast (owner requirement, 2026-09-14): replaces the old shared #saveBanner
// div (SharedBlocks/editorBlock.jelly) with the same window.notificationBar singleton core
// uses for the "Apply" toast on /job/<name>/configure and /manage/configure.
function showSaveNotification(message, isError) {
  window.notificationBar.show(message,
      isError ? window.notificationBar.ERROR : window.notificationBar.SUCCESS);
}

function saveClicked(activate) {
  var content = (editMode && overrideEditor) ? overrideEditor.getValue() : lastEditContent;
  document.getElementById('baseChainField').value = JSON.stringify(baseChainRows);
  var note = document.getElementById('noteField').value;
  var payload = {
    content: content,
    note: note,
    activate: activate,
    baseChainJson: JSON.stringify(baseChainRows),
    // Only meaningful (and only ever read server-side) on this job's very first save while
    // the base chain is empty — mirrors EnvConfigSetPage's equivalent contentType field, minus
    // the explicitlyStandalone gate.
    contentType: baseChainRows.length === 0 ? currentContentType : null
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
    document.getElementById('noteField').value = '';
    if (r.contentTypeLocked) { applyContentTypeLocked(r.contentTypeValue); }
    if (r.exists) { applyConfigSetNowExists(); }
    lastSavedOverrideContent = content;
    lastSavedBaseChainJson = JSON.stringify(baseChainRows);
    lastSavedContentType = currentContentType;
  });
}

// Bug fix (2026-09-28): this job's very first successful save also flips the record from "does
// not exist yet" to existing — without a page reload, the stale "does not exist yet" banner must
// be brought in line with that, exactly mirroring CommonConfigSetPage/index.js's identical fix.
// This page has no Delete button to reveal (job-scoped Config Chain content is deleted by
// clearing the base chain and override, not through a standalone lifecycle action), so this only
// ever touches the banner.
function applyConfigSetNowExists() {
  var banner = document.getElementById('notExistYetBanner');
  if (banner) { banner.style.display = 'none'; }
}

function discardAllChangesClicked() {
  if (!window.confirm('Discard all unsaved changes to the base chain and override? '
      + 'This cannot be undone.')) {
    return;
  }
  baseChainRows = JSON.parse(lastSavedBaseChainJson);
  baseChainRowExpanded = baseChainRows.map(function () { return false; });
  updateContentTypeRowVisibility();
  renderBaseChainRows();

  currentContentType = lastSavedContentType;
  if (!document.getElementById('contentTypeLockedDisplay')) {
    var contentTypeRadios = document.getElementsByName('contentTypeRadio');
    for (var ct = 0; ct < contentTypeRadios.length; ct++) {
      contentTypeRadios[ct].checked = (contentTypeRadios[ct].value === currentContentType);
    }
  }

  lastEditContent = lastSavedOverrideContent;
  clearTimeout(debounceHandle);
  if (editMode && overrideEditor) {
    overrideEditor.dispose();
    overrideEditor = null;
  }
  editMode = false;
  switchToEditMode();
}

function renderVersionHistoryRows(versions) {
  var tbody = document.getElementById('versionHistoryTbody');
  tbody.innerHTML = '';
  if (versions.length === 0) {
    tbody.appendChild(buildEmptyStateRow(7, __seed.emptyVersions));
    reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
    refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
    document.getElementById('modeEditBtn').style.display = 'none';
    return;
  }
  for (var i = 0; i < versions.length; i++) {
    tbody.appendChild(buildHistoryRowElement(versions[i]));
  }
  reconcilePhantomScrollSoon(document.getElementById('versionHistoryTableWrap'));
  refreshTableFilters(); // re-apply any active column filter to the rebuilt rows
  document.getElementById('modeEditBtn').style.display = (versions.length >= 2) ? '' : 'none';
}

function buildHistoryRowElement(v) {
  var tpl = document.getElementById('historyRowTemplate');
  var tr = tpl.content.firstElementChild.cloneNode(true);
  tr.id = 'historyRow-' + v.versionNumber;
  // CSP migration: no direct tr.onclick/onkeydown/toggle.onclick/btn.onclick assignment here
  // any more — data-version-number lets the SAME delegated listeners wired for the
  // server-rendered rows (see the "Version history row delegation" section at the bottom of this
  // file) also cover rows rebuilt here, without double-firing a handler on this row.
  tr.setAttribute('data-version-number', v.versionNumber);
  tr.querySelector('.js-version').textContent = 'v' + v.versionNumber;
  tr.querySelector('.js-created').textContent = formatTimestamp(v.timestampEpochMillis);
  tr.querySelector('.js-createdBy').textContent = v.author;
  tr.querySelector('.js-note').textContent = v.note;

  var chain = v.baseChain || [];
  // No explicitlyStandalone concept here — the badge stays permanently hidden, always the
  // plain "N bases" link.
  var badge = tr.querySelector('.js-standalone-badge');
  badge.style.display = 'none';
  var toggle = tr.querySelector('.js-basechain-toggle');
  toggle.style.display = 'inline';
  toggle.setAttribute('data-version-number', v.versionNumber);
  tr.querySelector('.js-basechain-count').textContent = chain.length;
  var countLabel = tr.querySelector('.js-basechain-count').nextSibling;
  if (countLabel) {
    // Singular and plural are separate keys, not an English 's' appended at runtime:
    // most target languages do not pluralize that way.
    countLabel.textContent = ' ' + (chain.length === 1
        ? tpl.dataset.baseSingular : tpl.dataset.basePlural);
  }
  var detail = tr.querySelector('.js-basechain-detail');
  detail.id = 'baseChainDetail-' + v.versionNumber;
  if (chain.length === 0) {
    detail.textContent = tpl.dataset.noDeclaredChain;
  } else {
    detail.innerHTML = '';
    for (var c = 0; c < chain.length; c++) {
      var ref = chain[c];
      var line = document.createElement('div');
      line.textContent = ref.projectKey + ' — ' + ref.pinMode
          + (ref.pinMode === 'PINNED' ? ' (v' + ref.pinnedVersionNumber + ')' : '');
      detail.appendChild(line);
    }
  }

  var activeBadge = tr.querySelector('.js-activeBadge');
  activeBadge.textContent = '';
  if (v.active) { ctsyncSetIconText(activeBadge, 'active', tpl.dataset.activeLabel); }
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

function renderSecretsManifestRows(manifest) {
  var tbody = document.getElementById('secretsManifestTbody');
  tbody.innerHTML = '';
  if (manifest.length === 0) {
    tbody.appendChild(buildEmptyStateRow(3, __seed.emptySecrets));
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

function showGeneratedTemplateView() {
  // Owner requirement (2026-09-12): generate from the CURRENT, possibly-unsaved draft —
  // same content/base-chain/content-type arguments recomputeMerge() already sends to
  // proxy.previewMerge(...), reusing the exact same "editor content, whether saved or not"
  // fallback saveClicked() uses (editMode && overrideEditor ? live value : lastEditContent),
  // so this works identically whether the override editor is currently live or mid-Compare.
  var draftContent = (editMode && overrideEditor) ? overrideEditor.getValue() : lastEditContent;
  proxy.generateTemplate(draftContent, JSON.stringify(baseChainRows), currentContentType,
      function (t) {
    var r = t.responseObject();
    if (!r.ok) {
      alert(r.error);
      return;
    }
    lastGeneratedTemplateText = r.template;
    document.getElementById('mergeLayout').style.display = 'none';
    document.getElementById('generateBanner').style.display = 'block';
    document.getElementById('generatedTemplatePanel').style.display = 'block';
    document.getElementById('generateTemplateBtn').style.display = 'none';
    document.getElementById('backTo3PanelBtn').style.display = 'inline-block';
    if (generatedTemplateEditor) {
      generatedTemplateEditor.setValue(lastGeneratedTemplateText);
      monaco.editor.setModelLanguage(generatedTemplateEditor.getModel(), languageForType(r.contentType));
    } else {
      generatedTemplateEditor = monaco.editor.create(document.getElementById('generatedTemplateEditor'), {
        value: lastGeneratedTemplateText,
        language: languageForType(r.contentType),
        readOnly: true,
        automaticLayout: true
      });
    }
  });
}

function backTo3PanelView() {
  document.getElementById('mergeLayout').style.display = '';
  document.getElementById('generateBanner').style.display = 'none';
  document.getElementById('generatedTemplatePanel').style.display = 'none';
  document.getElementById('generateTemplateBtn').style.display = 'inline-block';
  document.getElementById('backTo3PanelBtn').style.display = 'none';
}

function copyGeneratedTemplate() {
  navigator.clipboard.writeText(
      generatedTemplateEditor ? generatedTemplateEditor.getValue() : lastGeneratedTemplateText);
}

function setActivateButtonsDisabled(disabled) {
  var buttons = document.querySelectorAll('.ctsync-activate-btn');
  for (var i = 0; i < buttons.length; i++) {
    if (buttons[i].getAttribute('data-always-disabled') === 'true') { continue; }
    buttons[i].disabled = disabled;
  }
}

function activateVersion(v) {
  setActivateButtonsDisabled(true);
  proxy.activate(JSON.stringify({ version: v }), function (t) {
    var r = t.responseObject();
    if (r.ok) {
      renderVersionHistoryRows(r.versions);
      reloadEditorStateFromActivatedVersion(r);
    } else {
      setActivateButtonsDisabled(false);
      alert(r.error);
    }
  });
}

// Mirrors EnvConfigSetPage's identical function, minus the explicitlyStandalone field
// (r.activatedExplicitlyStandalone is deliberately never sent by this page's server side —
// see ConfigTemplatesJobAction#activateImpl).
function reloadEditorStateFromActivatedVersion(r) {
  var activatedChain = r.activatedBaseChain || [];
  baseChainRows = [];
  for (var i = 0; i < activatedChain.length; i++) {
    var ref = activatedChain[i];
    baseChainRows.push({ projectKey: ref.projectKey, pinMode: ref.pinMode,
        pinnedVersionNumber: ref.pinnedVersionNumber });
  }
  baseChainRowExpanded = baseChainRows.map(function () { return false; });
  updateContentTypeRowVisibility();

  currentContentType = r.activatedContentType || currentContentType;
  lastEditContent = r.activatedContent != null ? r.activatedContent : '{}';

  clearTimeout(debounceHandle);
  if (diffEditor) { diffEditor.dispose(); diffEditor = null; }
  if (editMode && overrideEditor) { overrideEditor.dispose(); overrideEditor = null; }
  editMode = false;
  switchToEditMode();

  lastSavedOverrideContent = lastEditContent;
  lastSavedBaseChainJson = JSON.stringify(baseChainRows);
  lastSavedContentType = currentContentType;
}

function addSecret() {
  var path = document.getElementById('secretPath').value;
  var cred = document.getElementById('secretCredentialId').value;
  proxy.addSecret(JSON.stringify({ path: path, credentialId: cred }), function (t) {
    var r = t.responseObject();
    if (r.ok) {
      renderSecretsManifestRows(r.secretsManifest);
      document.getElementById('secretPath').value = '';
    } else {
      alert(r.error);
    }
  });
}

function removeSecret(path) {
  proxy.removeSecret(JSON.stringify({ path: path }), function (t) {
    var r = t.responseObject();
    if (r.ok) { renderSecretsManifestRows(r.secretsManifest); } else { alert(r.error); }
  });
}

// ---- CSP migration: wiring that used to be inline onclick="..."/onchange="..."/onkeydown="..."
// HTML attributes across ConfigTemplatesJobAction/index.jelly and the four SharedBlocks fragments
// it includes (editorBlock, baseChainBlock, versionHistoryBlock, secretsManifestBlock). ----

(function () {
  function on(id, event, handler) {
    var el = document.getElementById(id);
    if (el) { el.addEventListener(event, handler); }
  }

  // editorBlock.jelly
  on('generateTemplateBtn', 'click', showGeneratedTemplateView);
  on('backTo3PanelBtn', 'click', backTo3PanelView);
  on('copyTemplateBtn', 'click', copyGeneratedTemplate);
  on('loadComparedBtn', 'click', loadComparedIntoEditor);
  on('backToEditingBtn', 'click', switchToEditMode);
  on('modeEditBtn', 'click', switchToEditMode);
  on('saveBtn', 'click', function () { saveClicked(false); });
  on('saveActivateBtn', 'click', function () { saveClicked(true); });
  on('discardAllBtn', 'click', discardAllChangesClicked);
  Array.prototype.forEach.call(document.querySelectorAll('input[name="contentTypeRadio"]'),
      function (radio) {
        radio.addEventListener('click', function () { onContentTypeChange(this.value); });
      });

  // baseChainBlock.jelly
  on('addBaseChainRowBtn', 'click', addBaseChainRow);
  var standaloneCheckbox = document.getElementById('explicitlyStandaloneCheckbox');
  if (standaloneCheckbox) {
    standaloneCheckbox.addEventListener('change', function () {
      onExplicitlyStandaloneChange(this.checked);
    });
  }

  // versionHistoryBlock.jelly — server-rendered rows carry data-version-number (see that file);
  // buildHistoryRowElement() above sets the identical attribute on rows it rebuilds, so this one
  // delegated listener set on the persistent #versionHistoryTbody covers both without ever
  // double-firing (there is no longer any direct onclick/onkeydown property on the row itself).
  var versionHistoryTbody = document.getElementById('versionHistoryTbody');
  if (versionHistoryTbody) {
    versionHistoryTbody.addEventListener('click', function (e) {
      var toggle = e.target.closest && e.target.closest('.ctsync-basechain-toggle');
      if (toggle) {
        e.stopPropagation();
        e.preventDefault();
        toggleBaseChainDetail(toggle.getAttribute('data-version-number'));
        return;
      }
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

  // secretsManifestBlock.jelly
  on('addSecretBtn', 'click', addSecret);
  var secretsManifestTbody = document.getElementById('secretsManifestTbody');
  if (secretsManifestTbody) {
    secretsManifestTbody.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('.ctsync-remove-secret-btn');
      if (btn) { removeSecret(btn.getAttribute('data-secret-path')); }
    });
  }
})();
